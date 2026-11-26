package io.github.rajami1205.filesystem.application;

import io.github.rajami1205.filesystem.model.disk.DiskGeometry;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import static io.github.rajami1205.filesystem.application.RuntimeWire.*;

/** Client transport. Session and handle authority never cross the process boundary. */
public final class RuntimeClient implements AutoCloseable {
    public record Context(UUID session,int state,int uid,int home,int cwd) {}
    public static final class Failure extends IOException {
        public final int code,outcome;
        Failure(int code,int outcome) { super("Runtime error "+code); this.code=code; this.outcome=outcome; }
        byte[] payload() { return error(code,outcome); }
    }
    public static final class StartupFailure extends IOException {
        public final int result;
        public final UUID disk,instance;
        StartupFailure(Bootstrap.Result r) {
            super(r.code()==3 ? "OUTCOME_UNKNOWN" : "Format startup result "+r.code());
            result=r.code(); disk=r.disk(); instance=r.instance();
        }
    }
    static byte[] error(int code,int outcome) {
        return ByteBuffer.allocate(3).putShort((short)code).put((byte)outcome).array();
    }
    private final Socket socket;
    private final UUID instance;
    private long last;
    private RuntimeClient(Socket socket,Discovery d,long last) { this.socket=socket; instance=d.instance(); this.last=last; }
    public UUID instance() { return instance; }

    private static ProcessBuilder javaProcess(Class<?> entry, String... args) {
        var entries=System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator),-1);
        for(int i=0;i<entries.length;i++) entries[i]=Path.of(entries[i]).toAbsolutePath().normalize().toString();
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp", String.join(File.pathSeparator,entries), entry.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
    }
    static Process start(Path path,boolean format) throws IOException {
        var builder = javaProcess(RuntimeMain.class, format ? "format" : "mount", path.toString());
        if (!format) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    /** Private pipe reader. Its lifetime can be bounded independently of the persistent owner.
     * Validate EOF here before forwarding the single receipt and exiting. */
    public static final class ReceiptReader {
        public static void main(String[] args) {
            try { Bootstrap.Result.read(System.in).write(System.out); }
            catch (IOException invalidOrMissing) { /* Empty output means no validated receipt. */ }
        }
    }

    @FunctionalInterface
    interface FormatPipeline { List<Process> start(Path target) throws IOException; }
    static final ScopedValue<FormatPipeline> FORMAT_PIPELINE = ScopedValue.newInstance();
    private static final int MAX_FORMAT_READERS = 32;
    private static final Set<FormatExchange> reservations = new HashSet<>();

    static boolean ownsReader(Process reader) {
        List<FormatExchange> snapshot;
        synchronized (reservations) { snapshot = List.copyOf(reservations); }
        for (var entry : snapshot) {
            synchronized (entry) { if (entry.reader == reader) return true; }
        }
        return false;
    }

    private static FormatExchange reserveReader() throws StartupFailure {
        List<FormatExchange> snapshot;
        synchronized (reservations) { snapshot = List.copyOf(reservations); }
        for (var entry : snapshot) entry.reconcile();
        synchronized (reservations) {
            if (reservations.size() >= MAX_FORMAT_READERS)
                throw new StartupFailure(new Bootstrap.Result(2,Bootstrap.ZERO,Bootstrap.ZERO));
            var exchange = new FormatExchange();
            reservations.add(exchange);
            return exchange;
        }
    }

    static final class FormatExchange {
        private boolean abandoned, sent;
        private Process reader;
        private Bootstrap.Result receipt;
        private IOException cleanupFailure;
        private final CountDownLatch workerFinished = new CountDownLatch(1);

        private IOException pending;

        private void release() {
            synchronized (reservations) { reservations.remove(this); }
        }

        private synchronized void confirmedExit() {
            reader = null;
            release();
        }

        private void reconcile() {
            Process retained;
            synchronized (this) {
                retained = workerFinished.getCount()==0 && cleanupFailure!=null ? reader : null;
            }
            if (retained != null && !retained.isAlive()) confirmedExit();
        }

        private synchronized void finishedWithoutReader() {
            if (reader == null) {
                workerFinished.countDown();
                release();
            }
        }

        IOException abandon() {
            Process active;
            synchronized (this) {
                abandoned = true;
                active = reader;
            }
            boolean cleaned = true;
            if (active != null) {
                try { active.destroyForcibly(); }
                catch (RuntimeException e) { recordCleanup(e); }
                long cleanupLimit = deadline(3);
                boolean interrupted = Thread.interrupted();
                try {
                    do {
                        try {
                            workerFinished.await(Math.max(0,cleanupLimit-System.nanoTime()),TimeUnit.NANOSECONDS);
                            break;
                        } catch (InterruptedException e) { interrupted = true; }
                    } while (System.nanoTime() < cleanupLimit);
                    cleaned = workerFinished.getCount()==0;
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
            synchronized (this) {
                IOException failure = sent ? startupTimeout(receipt)
                        : new StartupFailure(new Bootstrap.Result(2,Bootstrap.ZERO,Bootstrap.ZERO));
                if (cleanupFailure != null) failure.addSuppressed(cleanupFailure);
                if (!cleaned || (active == null && workerFinished.getCount()!=0)) {
                    if (pending == null) pending = new IOException("Receipt reader cleanup pending");
                    failure.addSuppressed(pending);
                }
                return failure;
            }
        }

        private synchronized void recordCleanup(Throwable cause) {
            if (cleanupFailure == null) {
                cleanupFailure = new IOException("Receipt reader cleanup failed", cause);
                if (pending != null) pending.addSuppressed(cleanupFailure);
            } else cleanupFailure.addSuppressed(cause);
        }

        Bootstrap.Result transfer(Process candidate, Process receiptReader, byte[] bytes, long limit) throws IOException {
            StartupFailure primary = null;
            synchronized (this) { reader = receiptReader; }
            try (var out = candidate.getOutputStream(); var in = receiptReader.getInputStream()) {
                synchronized (this) {
                    if (abandoned || System.nanoTime() >= limit)
                        throw new StartupFailure(new Bootstrap.Result(2,Bootstrap.ZERO,Bootstrap.ZERO));

                    // A single bounded OSFB on an initially empty pipe (under 1 KiB for D03-valid input).
                    sent = true;
                }
                out.write(bytes);
                out.close();
                if (!receiptReader.waitFor(Math.max(0,limit-System.nanoTime()),TimeUnit.NANOSECONDS))
                    throw startupTimeout(null);
                synchronized (this) {
                    if (abandoned || System.nanoTime() >= limit) throw startupTimeout(null);
                }
                var validated = acknowledgement(in);
                synchronized (this) {
                    if (abandoned || System.nanoTime() >= limit) throw startupTimeout(null);
                    receipt = validated;
                    return receipt;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                primary = startupTimeout(null);
                throw primary;
            } catch (StartupFailure e) {
                primary = e;
                throw e;
            } catch (IOException e) {
                primary = startupTimeout(receipt);
                primary.addSuppressed(e);
                throw primary;
            } finally {
                try {
                    if (receiptReader.isAlive()) receiptReader.destroyForcibly();
                    receiptReader.onExit().orTimeout(2,TimeUnit.SECONDS).join();
                    confirmedExit();
                } catch (RuntimeException e) {
                    recordCleanup(e);
                    // A failed confirmation does not end ownership. Admission also reconciles missed exits.
                    try { receiptReader.onExit().whenComplete((process, error) -> {
                        if (error == null) confirmedExit();
                        else recordCleanup(error);
                    }); }
                    catch (RuntimeException observationFailure) { recordCleanup(observationFailure); }
                } finally {
                    workerFinished.countDown();
                }
                synchronized (this) {
                    if (cleanupFailure != null) {
                        if (primary != null) primary.addSuppressed(cleanupFailure);
                        else {
                            var failure = startupTimeout(receipt);
                            failure.addSuppressed(cleanupFailure);
                            throw failure;
                        }
                    }
                }
            }
        }
    }
    public static RuntimeClient mount(Path path) throws IOException {
        return acquire(()->new Failure(RUNTIME_UNAVAILABLE,0),limit->discover(path.toRealPath(),limit,null));
    }
    public static RuntimeClient format(Path path,DiskGeometry geometry,String volume,char[] password,char[] confirmation)
            throws IOException {
        if(password==null || confirmation==null || password.length>128 || confirmation.length>128)
            throw new IllegalArgumentException("Invalid credential");
        var input=new Bootstrap(geometry,volume,password.clone(),confirmation.clone());
        var pipeline = FORMAT_PIPELINE.orElse(target -> ProcessBuilder.startPipeline(List.of(
                javaProcess(RuntimeMain.class,"format",target.toString()), javaProcess(ReceiptReader.class))));
        FormatExchange exchange;
        try { exchange = reserveReader(); }
        catch (StartupFailure full) { input.close(); throw full; }
        try {
            return acquire(exchange::abandon,limit->{
                try(input) { return format(path,input,limit,exchange,pipeline); }
                finally { exchange.finishedWithoutReader(); }
            });
        } catch (RuntimeException | Error failure) {
            // Includes failure to start the acquisition worker.
            exchange.finishedWithoutReader();
            input.close();
            throw failure;
        }
    }
    @FunctionalInterface
    interface Acquisition { RuntimeClient run(long limit) throws IOException; }

    // Host path lookup and process creation may block outside socket timeout control.
    private static RuntimeClient acquire(java.util.function.Supplier<IOException> timeout,Acquisition operation) throws IOException {
        return acquire(timeout,operation,deadline(30));
    }
    static RuntimeClient acquire(java.util.function.Supplier<IOException> timeout, Acquisition operation, long limit)
            throws IOException {
        var completed=new CompletableFuture<RuntimeClient>();
        Thread.startVirtualThread(()->{
            try { completed.complete(operation.run(limit)); }
            catch(Throwable failure) { completed.completeExceptionally(failure); }
        });
        try { return completed.get(Math.max(0,limit-System.nanoTime()),TimeUnit.NANOSECONDS); }
        catch(ExecutionException e) {
            if(e.getCause() instanceof IOException io) throw io;
            if(e.getCause() instanceof RuntimeException invalid) throw invalid;
            if(e.getCause() instanceof Error fatal) throw fatal;
            throw new IOException(e.getCause());
        } catch(TimeoutException | InterruptedException e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            completed.thenAccept(client->RuntimeWire.close(client.socket));
            throw timeout.get();
        }
    }
    private static RuntimeClient format(Path path,Bootstrap input,long limit,
            FormatExchange exchange, FormatPipeline pipeline) throws IOException {
        Path absolute=path.toAbsolutePath();
        Path target=absolute.getParent()==null ? absolute.toRealPath() :
                absolute.getParent().toRealPath().resolve(absolute.getFileName());
        // Prepare bytes before starting a child. The worker owns the bytes until its pipe write completes.
        byte[] bytes;
        var b=ByteBuffer.allocate(4096);
        try {
            var output=new OutputStream() {
                public void write(int n) { b.put((byte)n); }
                public void write(byte[] data,int off,int len) { b.put(data,off,len); }
            };
            input.write(output);
            bytes=used(b);
        } finally { Arrays.fill(b.array(),(byte)0); }
        Bootstrap.Result result;
        try {
            if (System.nanoTime() >= limit)
                throw new StartupFailure(new Bootstrap.Result(2,Bootstrap.ZERO,Bootstrap.ZERO));
            var processes = pipeline.start(target);
            result = exchange.transfer(processes.getFirst(), processes.getLast(), bytes, limit);
        } finally { Arrays.fill(bytes,(byte)0); }
        if(result.code()!=0) throw new StartupFailure(result);
        try { return discover(target,limit,result); }
        catch(IOException e) { throw new StartupFailure(new Bootstrap.Result(4,result.disk(),result.instance())); }
    }
    private static StartupFailure startupTimeout(Bootstrap.Result result) {
        if(result==null) return new StartupFailure(new Bootstrap.Result(3,Bootstrap.ZERO,Bootstrap.ZERO));
        return new StartupFailure(result.code()==0 ? new Bootstrap.Result(4,result.disk(),result.instance()) : result);
    }
    static Bootstrap.Result acknowledgement(InputStream candidateOutput) throws StartupFailure {
        try { return Bootstrap.Result.read(candidateOutput); }
        catch(IOException lost) { throw startupTimeout(null); }
    }

    private static RuntimeClient discover(Path path,long limit,Bootstrap.Result candidate) throws IOException {
        boolean launched=candidate!=null;
        long backoff=10;
        while(System.nanoTime()<limit) {
            try(var d=Discovery.read(path)) {
                if(candidate!=null && (!candidate.disk().equals(d.disk()) || !candidate.instance().equals(d.instance())))
                    throw new Protocol();
                Socket socket=new Socket();
                try {
                    int millis=(int)Math.max(1,Math.min(1000,TimeUnit.NANOSECONDS.toMillis(limit-System.nanoTime())));
                    socket.connect(new InetSocketAddress(Discovery.LOOPBACK,d.port()),millis);
                    return new RuntimeClient(socket,d,handshake(socket,d,true,limit));
                } catch(IOException | RuntimeException e) { RuntimeWire.close(socket); throw e; }
            } catch(Failure e) {
                if(e.code==CONNECTION_LIMIT) throw e;
            } catch(IOException e) {
                if(!launched && System.nanoTime()<limit) {
                    launched=true; start(path,false).getOutputStream().close();
                }
            }
            try { TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(backoff),Math.max(0,limit-System.nanoTime()))); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
            backoff=Math.min(backoff*2,250);
        }
        throw new Failure(RUNTIME_UNAVAILABLE,0);
    }
    public Context login(String name,char[] password) throws IOException { return credentials(LOGIN,name,password); }
    public Context switchIdentity(String name,char[] password) throws IOException { return credentials(SWITCH_IDENTITY,name,password); }
    private Context credentials(int type,String name,char[] password) throws IOException {
        var b=ByteBuffer.allocate(548);
        try {
            putText(b,CharBuffer.wrap(name),32); putText(b,CharBuffer.wrap(password),512);
            return context(request(type,used(b),SESSION));
        } finally { Arrays.fill(b.array(),(byte)0); }
    }
    public Context session() throws IOException { return context(request(GET_SESSION,EMPTY,SESSION)); }
    public Context setCwd(String path) throws IOException {
        var b=ByteBuffer.allocate(4098); putText(b,CharBuffer.wrap(path),4096);
        return context(request(SET_CWD,used(b),SESSION));
    }
    private Context context(byte[] data) throws IOException {
        var b=ByteBuffer.wrap(data);
        var c=new Context(uuid(b),Byte.toUnsignedInt(b.get()),b.getInt(),b.getInt(),b.getInt());
        if(c.state()!=2 || c.uid()<0 || c.uid()>=128 || c.home()<1 || c.cwd()<0) {
            RuntimeWire.close(socket);
            throw new Protocol();
        }
        return c;
    }
    private synchronized byte[] request(int type,byte[] data,int response) throws IOException {
        try {
            if(last==Long.MAX_VALUE) throw new Protocol();
            send(socket.getOutputStream(),type,++last,data);
            try(var f=read(socket.getInputStream(),0,last,t->t==response || t==ERROR)) {
                if(f.type()==ERROR) {
                    var b=f.payload(); int code=Short.toUnsignedInt(b.getShort()), outcome=Byte.toUnsignedInt(b.get());
                    if(code<1 || code>11 || outcome>2) throw new Protocol();
                    throw new Failure(code,outcome);
                }
                return f.data().clone();
            }
        } catch(Failure e) { throw e; }
        catch(Protocol e) { RuntimeWire.close(socket); throw e; }
        catch(IOException e) {
            RuntimeWire.close(socket);
            // Current OSIP requests mutate only connection-local state. OSFB has a separate receipt.
            throw new IOException("IPC_LOST",e);
        }
        finally { Arrays.fill(data,(byte)0); }
    }
    public void exit() throws IOException { try { request(EXIT,EMPTY,OK); } finally { close(); } }
    public void close() throws IOException { socket.close(); }
}
