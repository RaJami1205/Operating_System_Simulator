package io.github.rajami1205.filesystem.application;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;
import static io.github.rajami1205.filesystem.application.RuntimeWire.*;

/** One instance owns admission, connection contexts and all volatile handles on one mounted disk. */
final class RuntimeServer implements AutoCloseable {
    enum Life { STARTING, INITIALIZING, READY, STOPPING, TERMINATED, FAULTED }
    enum State { CONNECTED, UNAUTHENTICATED, COMMAND, READER, EDITOR, CLOSED }
    record Handle(UUID id, UUID session, int fcb, boolean write, int uid, String origin, Instant opened) {}
    record Snapshot(Life lifecycle, int admitted, int authenticated, int handles, int distinctFiles) {}
    static final class Session {
        final UUID id=UUID.randomUUID();
        final Socket socket;
        State state=State.CONNECTED;
        OperationalFileSystem.Actor actor;
        int home=-1,cwd=-1;
        Session(Socket socket) { this.socket=socket; }
        byte[] snapshot() {
            var b=ByteBuffer.allocate(29); uuid(b,id);
            return b.put((byte)state.ordinal()).putInt(actor.uid()).putInt(home).putInt(cwd).array();
        }
    }
    final ReentrantLock lock=new ReentrantLock(true);
    private final java.util.concurrent.locks.Condition empty=lock.newCondition();
    private final Set<Session> sessions=new HashSet<>();
    private final Map<UUID,Handle> handles=new HashMap<>();
    private final OperationalFileSystem fs;
    final Discovery discovery;
    private final ServerSocket listener;
    private ScheduledFuture<?> grace;
    private long initialDeadline;
    private boolean published;
    private Life life=Life.STARTING;

    Snapshot snapshot() {
        lock.lock();
        try {
            int authenticated=(int)sessions.stream().filter(s->s.actor!=null).count();
            int distinctFiles=(int)handles.values().stream().map(Handle::fcb).distinct().count();
            return new Snapshot(life,sessions.size(),authenticated,handles.size(),distinctFiles);
        } finally { lock.unlock(); }
    }

    RuntimeServer(OperationalFileSystem fs,UUID instance) throws IOException {
        this.fs=fs;
        life=Life.INITIALIZING;
        var owner=fs.owner();
        listener=new ServerSocket();
        try {
            listener.bind(new InetSocketAddress(Discovery.LOOPBACK,0),32);
            discovery=new Discovery(owner.path(),owner.uuid(),instance,ProcessHandle.current().pid(),listener.getLocalPort(),nonce());
        } catch(IOException | RuntimeException e) { RuntimeWire.close(listener); throw e; }
    }
    void publish() throws IOException {
        lock.lock();
        try {
            discovery.publish(); published=true; life=Life.READY;
            initialDeadline=deadline(10);
            grace=until(initialDeadline,()->Thread.startVirtualThread(()->{
                lock.lock();
                try { if(sessions.isEmpty() && life==Life.READY) stop(false); }
                finally { lock.unlock(); }
            }));
        } finally { lock.unlock(); }
    }
    void serve() throws IOException {
        while(!listener.isClosed()) {
            Socket socket;
            try { socket=listener.accept(); }
            catch(SocketException e) { if(listener.isClosed()) return; throw e; }
            Session session=admit(socket);
            if(session==null) { reject(socket); continue; }
            Thread.startVirtualThread(()->handle(session));
        }
    }
    // One bounded rejection on the acceptor, with no extra worker or admission slot.
    private void reject(Socket socket) {
        int code;
        lock.lock();
        try { code=life==Life.READY ? CONNECTION_LIMIT : RUNTIME_STOPPING; }
        finally { lock.unlock(); }
        var cancel=until(System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200),
                ()->RuntimeWire.close(socket));
        try(socket; var hello=read(socket.getInputStream(),0,0,t->t==HELLO)) {
            send(socket.getOutputStream(),ERROR,hello.id(),RuntimeClient.error(code,0));
        } catch(IOException ignored) {
            // A partial HELLO cannot be correlated safely.
        } finally { cancel.cancel(false); }
    }
    Session admit(Socket socket) {
        lock.lock();
        try {
            if(life==Life.READY && initialDeadline!=0 && System.nanoTime()-initialDeadline>=0) stop(false);
            if(life!=Life.READY || sessions.size()>=32) return null;
            var s=new Session(socket);
            sessions.add(s);
            initialDeadline=0;
            grace.cancel(false);
            return s;
        } finally { lock.unlock(); }
    }
    private void stop(boolean fault) {
        life=fault ? Life.FAULTED : Life.STOPPING;
        RuntimeWire.close(listener);
        for(Session s:sessions) RuntimeWire.close(s.socket);
    }
    void disconnect(Session s) {
        lock.lock();
        try {
            if(!sessions.remove(s)) return;
            handles.values().removeIf(h->h.session().equals(s.id));
            s.actor=null; s.home=s.cwd=-1; s.state=State.CLOSED;
            if(sessions.isEmpty()) {
                if(life==Life.READY) stop(false);
                empty.signalAll();
            }
        } finally { lock.unlock(); RuntimeWire.close(s.socket); }
    }
    private void check(Session s) throws RuntimeClient.Failure {
        if(fs.faulted()) { stop(true); throw new RuntimeClient.Failure(STORAGE_FAULTED,0); }
        if(life!=Life.READY || !sessions.contains(s) || s.socket.isClosed())
            throw new RuntimeClient.Failure(RUNTIME_STOPPING,0);
    }
    Handle acquire(Session s,String path,boolean write) throws IOException {
        lock.lock();
        try {
            check(s);
            if(s.state!=State.COMMAND) throw new SecurityException();
            int fcb=fs.regularFile(s.actor,s.cwd,path,write);
            for(var h:handles.values()) if(h.fcb()==fcb && (h.write() || write))
                throw new RuntimeClient.Failure(FILE_IN_USE,0);
            var h=new Handle(UUID.randomUUID(),s.id,fcb,write,s.actor.uid(),path,Instant.now());
            handles.put(h.id(),h); return h;
        } finally { lock.unlock(); }
    }
    Handle lookup(Session s,UUID id) {
        lock.lock();
        try {
            var h=handles.get(id);
            if(h==null || !sessions.contains(s) || !h.session().equals(s.id)) throw new SecurityException();
            return h;
        } finally { lock.unlock(); }
    }
    void release(Session s,UUID id) {
        lock.lock();
        try { if(handles.containsKey(id)) { lookup(s,id); handles.remove(id); } }
        finally { lock.unlock(); }
    }
    private static boolean permitted(State state,int t) {
        if(t==EXIT) return true;
        return state==State.UNAUTHENTICATED ? t==LOGIN :
                state==State.COMMAND && (t==GET_SESSION || t==SET_CWD || t==SWITCH_IDENTITY);
    }
    byte[] execute(Session s,int type,ByteBuffer b) throws IOException {
        if(type==LOGIN || type==SWITCH_IDENTITY) {
            String name=text(b,32); char[] password=chars(b,512);
            try {
                end(b);
                return fs.authenticate(name,password,lock,(actor,home)->{
                    check(s);
                    if(!permitted(s.state,type)) throw new Protocol();
                    if(handles.values().stream().anyMatch(h->h.session().equals(s.id)))
                        throw new RuntimeClient.Failure(FILE_IN_USE,0);
                    int cwd;
                    try { cwd=fs.directory(actor,home,"."); }
                    catch(SecurityException denied) {
                        try { cwd=fs.directory(actor,0,"/"); }
                        catch(SecurityException inaccessible) { throw new RuntimeClient.Failure(ACCESS_DENIED,0); }
                    }
                    s.actor=actor; s.home=home; s.cwd=cwd; s.state=State.COMMAND;
                    return s.snapshot();
                });
            } catch(SecurityException e) { throw new RuntimeClient.Failure(AUTH_FAILED,0); }
            finally { Arrays.fill(password,'\0'); }
        }
        return coordinate(s,()->{
            if(!permitted(s.state,type)) throw new Protocol();
            if(type==SET_CWD) {
                String path=text(b,4096); end(b); s.cwd=fs.directory(s.actor,s.cwd,path);
            } else end(b);
            return type==EXIT ? EMPTY : s.snapshot();
        });
    }
    @FunctionalInterface
    interface Operation<T> { T run() throws IOException; }

    <T> T coordinate(Session s,Operation<T> operation) throws IOException {
        lock.lock();
        try {
            check(s);
            return operation.run();
        } finally {
            if(fs.faulted()) stop(true);
            lock.unlock();
        }
    }
    private void handle(Session s) {
        try {
            long last=handshake(s.socket,discovery,false,deadline(10));
            lock.lock();
            try { check(s); s.state=State.UNAUTHENTICATED; } finally { lock.unlock(); }
            var in=s.socket.getInputStream(); var out=s.socket.getOutputStream();
            while(true) {
                State state=s.state;
                try(var f=read(in,last,0,t->permitted(state,t))) {
                    last=f.id();
                    if (!respond(s,f,out)) return;
                }
            }
        } catch(IOException | RuntimeException ignored) {
            // No secrets or transport details are emitted to other clients.
        } finally {
            lock.lock();
            try { if(fs.faulted()) stop(true); } finally { lock.unlock(); }
            disconnect(s);
        }
    }
    boolean respond(Session s, Frame f, OutputStream out) throws IOException {
        int reply=f.type()==EXIT ? OK : SESSION;
        byte[] result;
        try { result=execute(s,f.type(),f.payload()); }
        catch(RuntimeClient.Failure e) {
            if (e.code == STORAGE_FAULTED) return false; // check() already closed every transport.
            reply=ERROR; result=e.payload();
        }
        catch(SecurityException e) { reply=ERROR; result=RuntimeClient.error(ACCESS_DENIED,0); }
        catch(DirectoryHierarchy.MissingEntry e) { reply=ERROR; result=RuntimeClient.error(NOT_FOUND,0); }
        catch(DirectoryHierarchy.InvalidType | UnsupportedOperationException e) { reply=ERROR; result=RuntimeClient.error(INVALID_TYPE,0); }
        catch(IllegalArgumentException e) { reply=ERROR; result=RuntimeClient.error(INVALID_REQUEST,0); }
        send(out,reply,f.id(),result);
        return f.type()!=EXIT;
    }

    public void close() throws IOException {
        lock.lock();
        try {
            if(life==Life.TERMINATED) return;
            stop(life==Life.FAULTED);
            if(grace!=null) grace.cancel(false);
            while(!sessions.isEmpty()) empty.awaitUninterruptibly();
            try { if(published) discovery.removeOwn(); }
            finally { discovery.close(); life=Life.TERMINATED; }
        } finally { lock.unlock(); }
    }
}
