package io.github.rajami1205.filesystem.application;

import io.github.rajami1205.filesystem.model.disk.DiskGeometry;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeIT {
    @TempDir Path dir;
    final DiskGeometry geometry=new DiskGeometry(1<<20,512);
    final char[] pass={'p'};
    static void terminated(Process p) throws Exception {
        if(!p.waitFor(15,TimeUnit.SECONDS)) {
            p.destroyForcibly(); assertTrue(p.waitFor(5,TimeUnit.SECONDS)); fail("Child exceeded its lifetime");
        }
    }
    static void awaitOwner(ProcessHandle observed) throws Exception {
        observed.onExit().get(15,TimeUnit.SECONDS);
    }
    record Peer(Process process,BufferedReader output,PrintWriter input) implements AutoCloseable {
        String line() throws Exception {
            var task=new FutureTask<String>(output::readLine);
            Thread.startVirtualThread(task);
            String value=task.get(35,TimeUnit.SECONDS); assertNotNull(value); return value;
        }
        public void close() throws Exception {
            input.close(); terminated(process); output.close();
        }
    }
    public static class ClientProcess {
        public static void main(String[] args) throws Exception {
            var input=new BufferedReader(new InputStreamReader(System.in));
            System.out.println("waiting");
            String command=input.readLine();
            if(command==null) return;
            Path path=Path.of(args[0]);
            try(var c=command.equals("format")
                    ? RuntimeClient.format(path,new DiskGeometry(1<<20,512),"v",new char[]{'p'},new char[]{'p'})
                    : RuntimeClient.mount(path)) {
                var s=c.login("root",new char[]{'p'});
                System.out.println(c.instance()+" "+s.session());
                String line;
                while((line=input.readLine())!=null && !line.equals("exit"))
                    System.out.println(c.setCwd(line).cwd());
                c.exit();
            }
        }
    }
    // Only the compiled driver lives outside the ZIP. Its child owner inherits the same classpath.
    Peer peer(Path path, Path distribution, Path driver) throws IOException {
        var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",
                distribution+File.pathSeparator+driver,ClientProcess.class.getName(),path.toString())
                .directory(distribution.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        return new Peer(p,p.inputReader(),new PrintWriter(p.outputWriter(),true));
    }
    private Path driver() throws IOException {
        Path driver = Files.createDirectory(dir.resolve("driver"));
        String resource = ClientProcess.class.getName().replace('.','/')+".class";
        Path destination = driver.resolve(resource);
        Files.createDirectories(destination.getParent());
        try (var in = ClientProcess.class.getResourceAsStream("/"+resource)) { Files.copy(in,destination); }
        return driver;
    }
    @Test void distributedClientAcquiresFormattedImage() throws Exception {
        Path distribution=Files.createDirectory(dir.resolve("dist"));
        extract(distribution);
        Path path=dir.resolve("format.fs");
        ProcessHandle owner;
        try(var client=peer(path,distribution,driver())) {
            assertEquals("waiting",client.line());
            client.input.println("format");
            String instance=client.line().split(" ")[0];
            try(var discovery=Discovery.read(path.toRealPath())) {
                assertEquals(discovery.instance().toString(),instance);
                owner=ProcessHandle.of(discovery.pid()).orElseThrow();
            }
            client.input.println("exit");
        }
        awaitOwner(owner);
        try(var ignored=OperationalFileSystem.mount(path)) {}
    }
    @Test void competingClientJvmsShareOwnerUntilFinalDisconnect() throws Exception {
        Path path=dir.resolve("disco ü.fs");
        try(var ignored=OperationalFileSystem.format(path,geometry,"v",pass,pass)) {}
        Files.write(Discovery.sidecar(path),new byte[]{0}); // Stale metadata may be replaced only by the lock winner.
        ProcessHandle observed;
        Path distribution = Files.createDirectory(dir.resolve("extracted distribution"));
        extract(distribution);
        Path driver = driver();
        try(var a=peer(path,distribution,driver);var b=peer(path,distribution,driver)) {
            assertEquals("waiting",a.line()); assertEquals("waiting",b.line());
            a.input.println("start"); b.input.println("start");
            var first=a.line().split(" "); var second=b.line().split(" ");
            assertEquals(first[0],second[0]); assertNotEquals(first[1],second[1]);
            try(var d=Discovery.read(path.toRealPath())) {
                assertEquals(d.instance().toString(),first[0]); observed=ProcessHandle.of(d.pid()).orElseThrow();
            }
            a.input.println("/user"); assertEquals("2",a.line());
            b.input.println("."); assertEquals("1",b.line());
            a.input.println("exit"); terminated(a.process);
            b.input.println("/"); assertEquals("0",b.line());
            assertThrows(IOException.class,()->OperationalFileSystem.mount(path));
            b.input.println("exit"); terminated(b.process);
        }
        awaitOwner(observed);
        assertFalse(Files.exists(Discovery.sidecar(path)));
        try(var ignored=OperationalFileSystem.mount(path)) {}
    }
    @Test void privateFormatConflictAndDuplicateDiskUuid() throws Exception {
        Path original=dir.resolve("a.fs"), copy=dir.resolve("b.fs");
        ProcessHandle owner;
        try(var a=RuntimeClient.format(original,geometry,"v",pass,pass);
            var d=Discovery.read(original.toRealPath())) {
            owner=ProcessHandle.of(d.pid()).orElseThrow();
            assertEquals(0,a.login("root",pass).uid());
            assertEquals(1,assertThrows(RuntimeClient.StartupFailure.class,
                    ()->RuntimeClient.format(original,geometry,"other",new char[]{'x'},new char[]{'x'})).result);
            assertEquals(0,a.session().uid());
            Path alias=dir.resolve("alias.fs");
            Files.createLink(alias,original);
            byte[] discoveryBefore = Files.readAllBytes(d.sidecar());
            var contender = fixture(AliasMount.class,alias.toString()).start();
            try {
                terminated(contender);
                assertEquals(0, contender.exitValue());
                assertEquals("LOCK_REJECTED", contender.inputReader().readLine());
            } finally {
                if (contender.isAlive()) { contender.destroyForcibly(); assertTrue(contender.waitFor(5,TimeUnit.SECONDS)); }
            }
            assertArrayEquals(discoveryBefore, Files.readAllBytes(d.sidecar()));
            assertFalse(Files.exists(Discovery.sidecar(alias)));
            assertEquals(0,a.session().uid());
        }
        awaitOwner(owner);
        Files.copy(original,copy);
        ProcessHandle first,second;
        try(var a=RuntimeClient.mount(original);var b=RuntimeClient.mount(copy);
            var x=Discovery.read(original.toRealPath());var y=Discovery.read(copy.toRealPath())) {
            assertEquals(x.disk(),y.disk()); assertNotEquals(a.instance(),b.instance());
            first=ProcessHandle.of(x.pid()).orElseThrow(); second=ProcessHandle.of(y.pid()).orElseThrow();
        }
        awaitOwner(first); awaitOwner(second);
    }
    @Test void formattedImageSurvivesPublicationFailure() throws Exception {
        Path path=dir.resolve("v.fs");
        Files.createDirectory(Discovery.sidecar(path));
        var failure=assertThrows(RuntimeClient.StartupFailure.class,()->RuntimeClient.format(path,geometry,"v",pass,pass));
        assertEquals(4,failure.result);
        try(var fs=OperationalFileSystem.mount(path)) { assertEquals(fs.owner().uuid(),failure.disk); }
    }

    @Test void bootstrapTimeoutPreservesMissingImage() throws Exception {
        Path path=dir.resolve("pending.fs");
        var p=RuntimeClient.start(path,true);
        try {
            var result=new FutureTask<Bootstrap.Result>(()->Bootstrap.Result.read(p.getInputStream()));
            Thread.startVirtualThread(result);
            p.getOutputStream().write(0x4f); p.getOutputStream().flush(); // Leave the private record incomplete.
            assertEquals(2,result.get(15,TimeUnit.SECONDS).code());
            terminated(p);
            assertFalse(Files.exists(path));
        } finally {
            p.getOutputStream().close();
            if(p.isAlive()) { p.destroyForcibly(); assertTrue(p.waitFor(5,TimeUnit.SECONDS)); }
        }
    }
    private static ProcessBuilder fixture(Class<?> entry, String... args) {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-cp",System.getProperty("java.class.path"),entry.getName()));
        command.addAll(List.of(args));
        return new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
    }
    public static class SilentCandidate {
        public static void main(String[] args) throws Exception {
            // Report exactly what reached stdin. Keep stdout open without acknowledging.
            if (args.length==0) System.err.println(System.in.readAllBytes().length);
            else System.err.println("INPUT_RETAINED");
            new CountDownLatch(1).await(25,TimeUnit.SECONDS);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"timeout","unreadInput","interrupt","beforeSend"})
    void abandonedPrivateExchangeReleasesReaderAndLeavesCandidateAlive(String mode) throws Exception {
        var children = ProcessBuilder.startPipeline(List.of(
                fixture(SilentCandidate.class,mode.equals("unreadInput") ? new String[]{"hold"} : new String[0])
                        .redirectError(ProcessBuilder.Redirect.PIPE),
                fixture(RuntimeClient.ReceiptReader.class)));
        Process candidate=children.getFirst(), reader=children.getLast();
        boolean before=mode.equals("beforeSend"), interrupt=before || mode.equals("interrupt");
        var gate=new CountDownLatch(before ? 1 : 0);
        var entered=new CountDownLatch(1);
        var worker=new java.util.concurrent.atomic.AtomicReference<Thread>();
        var interrupted=new java.util.concurrent.atomic.AtomicBoolean();
        var exchange=new RuntimeClient.FormatExchange();
        var call=new FutureTask<RuntimeClient.StartupFailure>(()->{
            try {
                return assertThrows(RuntimeClient.StartupFailure.class,()->RuntimeClient.acquire(exchange::abandon,limit->{
                    worker.set(Thread.currentThread());
                    entered.countDown();
                    try { if(!gate.await(5,TimeUnit.SECONDS)) throw new IOException("Test gate expired"); }
                    catch(InterruptedException e) { throw new IOException(e); }
                    exchange.transfer(candidate,reader,new byte[860],limit);
                    throw new AssertionError("Silent candidate acknowledged");
                },RuntimeWire.deadline(interrupt ? 10 : 2)));
            } finally { interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        Thread caller=Thread.startVirtualThread(call);
        try(var signals=candidate.errorReader()) {
            var bytes=new FutureTask<String>(signals::readLine);
            Thread.startVirtualThread(bytes);
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            if(!before) assertEquals(mode.equals("unreadInput") ? "INPUT_RETAINED" : "860",bytes.get(5,TimeUnit.SECONDS));
            if(interrupt) caller.interrupt();
            assertEquals(before ? 2 : 3,call.get(6,TimeUnit.SECONDS).result);
            assertEquals(interrupt,interrupted.get());
            gate.countDown();
            assertTrue(worker.get().join(java.time.Duration.ofSeconds(3)));
            assertTrue(reader.waitFor(2,TimeUnit.SECONDS));
            assertThrows(IOException.class,()->reader.getInputStream().available());
            if(before) assertEquals("0",bytes.get(3,TimeUnit.SECONDS));
            assertTrue(candidate.isAlive());
            assertThrows(IOException.class,()->{
                candidate.getOutputStream().write(1);
                candidate.getOutputStream().flush();
            });
        } finally {
            gate.countDown();
            for(Process child:children) {
                if(child.isAlive()) child.destroyForcibly();
                assertTrue(child.waitFor(5,TimeUnit.SECONDS));
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void readerConfirmationFailurePreservesOutcome(boolean acknowledged) throws Exception {
        var receipt=new Bootstrap.Result(0,UUID.randomUUID(),UUID.randomUUID());
        var bytes=new ByteArrayOutputStream();
        if(acknowledged) receipt.write(bytes);
        var injected=new TimeoutException("injected confirmation failure");
        class FixtureProcess extends Process {
            int destroys;
            public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
            public InputStream getInputStream() { return new ByteArrayInputStream(bytes.toByteArray()); }
            public InputStream getErrorStream() { return InputStream.nullInputStream(); }
            public int waitFor() { return 0; }
            public boolean waitFor(long timeout,TimeUnit unit) { return true; }
            public int exitValue() { return 0; }
            public void destroy() { destroys++; }
            public CompletableFuture<Process> onExit() { return CompletableFuture.failedFuture(injected); }
        }
        var candidate=new FixtureProcess();
        var reader=new FixtureProcess();
        var exchange=new RuntimeClient.FormatExchange();
        var failure=assertThrows(RuntimeClient.StartupFailure.class,
                ()->exchange.transfer(candidate,reader,new byte[1],RuntimeWire.deadline(3)));
        assertEquals(acknowledged ? 4 : 3,failure.result);
        assertEquals(acknowledged ? receipt.instance() : Bootstrap.ZERO,failure.instance);
        assertEquals(acknowledged ? receipt.disk() : Bootstrap.ZERO,failure.disk);
        assertEquals(1,failure.getSuppressed().length);
        var cleanup=failure.getSuppressed()[0];
        assertSame(injected,cleanup.getCause().getCause());
        var abandoned=assertTimeoutPreemptively(java.time.Duration.ofSeconds(4),exchange::abandon);
        assertSame(cleanup,abandoned.getSuppressed()[0]);
        assertEquals(1,reader.destroys);
        assertEquals(0,candidate.destroys);
    }

    private static final class ControlledProcess extends Process {
        final ByteArrayOutputStream input = new ByteArrayOutputStream();
        final CompletableFuture<Process> exit = new CompletableFuture<>();
        final CountDownLatch observing = new CountDownLatch(1);
        final TimeoutException unconfirmed = new TimeoutException("Injected exit confirmation");
        byte[] output = {};
        int confirmations, destroys;

        public OutputStream getOutputStream() { return input; }
        public InputStream getInputStream() { return new ByteArrayInputStream(output); }
        public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        public int waitFor() { throw new AssertionError("Unbounded wait"); }
        public boolean waitFor(long time,TimeUnit unit) { return true; }
        public int exitValue() {
            if (!exit.isDone()) throw new IllegalThreadStateException();
            return 0;
        }
        public void destroy() { destroys++; }
        public CompletableFuture<Process> onExit() {
            if (++confirmations == 1) return CompletableFuture.failedFuture(unconfirmed);
            observing.countDown();
            return exit;
        }
    }

    private RuntimeClient.StartupFailure formatFailure(RuntimeClient.FormatPipeline pipeline) {
        return assertThrows(RuntimeClient.StartupFailure.class,()->ScopedValue
                .where(RuntimeClient.FORMAT_PIPELINE,pipeline)
                .call(()->RuntimeClient.format(dir.resolve("fixture.fs"),geometry,"v",pass,pass)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void publicFormatRetainsUnconfirmedReader(boolean acknowledged) throws Exception {
        var candidate = new ControlledProcess();
        var reader = new ControlledProcess();
        var receipt = new Bootstrap.Result(0,UUID.randomUUID(),UUID.randomUUID());
        if (acknowledged) {
            var output = new ByteArrayOutputStream();
            receipt.write(output);
            reader.output = output.toByteArray();
        }
        try {
            var failure = formatFailure(path->List.of(candidate,reader));
            assertTrue(candidate.input.size()>0);
            assertEquals(acknowledged ? 4 : 3,failure.result);
            assertEquals(acknowledged ? receipt.disk() : Bootstrap.ZERO,failure.disk);
            assertEquals(acknowledged ? receipt.instance() : Bootstrap.ZERO,failure.instance);
            assertSame(reader.unconfirmed,failure.getSuppressed()[0].getCause().getCause());
            assertTrue(RuntimeClient.ownsReader(reader));
            assertEquals(0,candidate.destroys);
        } finally { reader.exit.complete(reader); }
        assertFalse(RuntimeClient.ownsReader(reader));
    }

    @Test void cancelledPendingRegistrationsRemainBoundedAndOwned() throws Exception {
        var pending = new CountDownLatch(32);
        var resume = new CountDownLatch(1);
        var callers = new ArrayList<Thread>();
        var failures = new ArrayList<FutureTask<RuntimeClient.StartupFailure>>();
        var readers = new ArrayList<ControlledProcess>();
        var candidates = new ArrayList<ControlledProcess>();
        try {
            for (int i=0;i<32;i++) {
                var candidate = new ControlledProcess();
                var reader = new ControlledProcess();
                readers.add(reader);
                candidates.add(candidate);
                var task = new FutureTask<>(()->formatFailure(path->{
                    pending.countDown();
                    try { if (!resume.await(10,TimeUnit.SECONDS)) throw new IOException("Fixture expired"); }
                    catch (InterruptedException e) { throw new IOException(e); }
                    return List.of(candidate,reader);
                }));
                failures.add(task);
                callers.add(Thread.startVirtualThread(task));
            }
            assertTrue(pending.await(5,TimeUnit.SECONDS));
            for (var caller : callers) caller.interrupt();
            for (var failure : failures) {
                var result = failure.get(3,TimeUnit.SECONDS);
                assertEquals(2,result.result);
                assertEquals("Receipt reader cleanup pending",result.getSuppressed()[0].getMessage());
            }
            assertEquals(2,formatFailure(path->{ throw new AssertionError("Launched while full"); }).result);
            resume.countDown();
            for (int i=0;i<32;i++) {
                var reader = readers.get(i);
                assertTrue(reader.observing.await(3,TimeUnit.SECONDS));
                assertTrue(RuntimeClient.ownsReader(reader));
                var diagnostic = failures.get(i).get().getSuppressed()[0];
                assertSame(reader.unconfirmed,diagnostic.getSuppressed()[0].getCause().getCause());
                assertEquals(0,candidates.get(i).input.size());
                assertEquals(0,candidates.get(i).destroys);
                assertEquals(1,reader.destroys);
            }
            assertEquals(2,formatFailure(path->{ throw new AssertionError("Evicted a live reader"); }).result);
        } finally {
            resume.countDown();
            for (var reader : readers) reader.exit.complete(reader);
        }
        var launched = new CountDownLatch(1);
        assertThrows(IOException.class,()->ScopedValue.where(RuntimeClient.FORMAT_PIPELINE,path->{
            launched.countDown(); throw new IOException("No reader created");
        }).call(()->RuntimeClient.format(dir.resolve("again.fs"),geometry,"v",pass,pass)));
        assertEquals(0,launched.getCount());
        for (int i=0;i<33;i++) {
            var exited = new ControlledProcess();
            exited.confirmations = 1;
            exited.exit.complete(exited);
            assertEquals(3,formatFailure(path->List.of(new ControlledProcess(),exited)).result);
            assertFalse(RuntimeClient.ownsReader(exited));
        }
    }

    public static class AliasMount {
        public static void main(String[] args) throws Exception {
            // RuntimeMain keeps mount failures private. Observe the existing exception at
            // its throw site while running the real candidate, even if it exits normally.
            Path trace = Files.createTempFile(Path.of(args[0]).getParent(),"alias-mount-", ".jfr");
            try (var recording = new jdk.jfr.Recording()) {
                recording.enable("jdk.JavaExceptionThrow").withStackTrace();
                recording.start();
                RuntimeMain.main(new String[]{"mount",args[0]});
                recording.stop();
                recording.dump(trace);
                boolean rejected = jdk.jfr.consumer.RecordingFile.readAllEvents(trace).stream()
                        .filter(event -> event.getEventType().getName().equals("jdk.JavaExceptionThrow"))
                        .filter(event -> "Image already locked".equals(event.getString("message")))
                        .anyMatch(event -> event.getStackTrace().getFrames().stream().anyMatch(frame ->
                                frame.getMethod().getType().getName().equals(
                                        "io.github.rajami1205.filesystem.infrastructure.disk.VirtualDisk")
                                        && frame.getMethod().getName().equals("lock")));
                if (rejected) System.out.println("LOCK_REJECTED");
            } finally { Files.deleteIfExists(trace); }
        }
    }
    private static void extract(Path distribution) throws IOException {
        try(var zip=new ZipFile("target/filesystem.zip")) {
            for(var item:zip.stream().filter(e->!e.isDirectory()).toList()) {
                Path dest=distribution.resolve(item.getName()); Files.createDirectories(dest.getParent());
                try(var in=zip.getInputStream(item)) { Files.copy(in,dest); }
            }
        }
    }
    @Test void extractedOwnerUsesOnlyDistributedClasspathAndExpiresInitialGrace() throws Exception {
        Path path=dir.resolve("new.fs");
        extract(dir);
        var p=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",".",
                RuntimeMain.class.getName(),"format",path.toString()).directory(dir.toFile()).start();
        try {
            try(var out=p.getOutputStream()) { new Bootstrap(geometry,"v",pass,pass).write(out); }
            var read=new FutureTask<Bootstrap.Result>(()->Bootstrap.Result.read(p.getInputStream()));
            Thread.startVirtualThread(read);
            var result=read.get(8,TimeUnit.SECONDS); assertEquals(0,result.code());
            try(var d=Discovery.read(path.toRealPath())) { assertEquals(result.instance(),d.instance()); }
            assertTrue(p.isAlive()); terminated(p);
            assertFalse(Files.exists(Discovery.sidecar(path)));
            try(var ignored=OperationalFileSystem.mount(path)) {}
        } finally { if(p.isAlive()) { p.destroyForcibly(); assertTrue(p.waitFor(5,TimeUnit.SECONDS)); } }
    }
}
