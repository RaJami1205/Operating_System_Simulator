package io.github.rajami1205.filesystem.application;

import io.github.rajami1205.filesystem.model.disk.*;
import io.github.rajami1205.filesystem.infrastructure.disk.VirtualDisk;
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.rajami1205.filesystem.application.RuntimeWire.*;

class RuntimeServerTest {
    @TempDir Path dir;
    final char[] pass=" secret é".toCharArray();
    Path path() { return dir.resolve("runtime.fs"); }
    OperationalFileSystem create() throws IOException {
        return OperationalFileSystem.format(path(),new DiskGeometry(1<<20,512),"volume",pass,pass);
    }
    ByteBuffer credentials(String name) throws IOException {
        var b=ByteBuffer.allocate(548); putText(b,CharBuffer.wrap(name),32); putText(b,CharBuffer.wrap(pass),512);
        return b.flip();
    }
    RuntimeServer.Session session(RuntimeServer server,String name) throws Exception {
        var s=server.admit(new Socket());
        s.state=RuntimeServer.State.UNAUTHENTICATED;
        server.execute(s,LOGIN,credentials(name)); return s;
    }
    @Test void handlesAndContextsAreConnectionBound() throws Exception {
        try(var fs=create()) {
            var root=fs.authenticate("root",pass);
            fs.createUser(root,"alice","Alice",null,pass,pass);
            fs.create(root,0,"/private",Fcb.Type.FILE);
        }
        byte[] before=Files.readAllBytes(path());
        try(var fs=OperationalFileSystem.mount(path()); var server=new RuntimeServer(fs,UUID.randomUUID())) {
            server.publish();
            var a=session(server,"root"); var b=session(server,"root"); var c=session(server,"alice");
            try {
                var first=server.acquire(a,"/private",false); server.acquire(b,"/private",false);
                assertEquals(new RuntimeServer.Snapshot(RuntimeServer.Life.READY,3,3,2,1),server.snapshot());
                assertEquals(FILE_IN_USE,assertThrows(RuntimeClient.Failure.class,()->server.acquire(a,"/private",true)).code);
                assertThrows(SecurityException.class,()->server.release(b,first.id()));
                assertThrows(SecurityException.class,()->server.acquire(c,"/private",false));
                assertEquals(FILE_IN_USE,assertThrows(RuntimeClient.Failure.class,()->server.execute(a,SWITCH_IDENTITY,credentials("alice"))).code);
                server.release(a,first.id()); server.release(a,first.id()); server.disconnect(b);
                var writer=server.acquire(a,"/private",true);
                assertEquals(FILE_IN_USE,assertThrows(RuntimeClient.Failure.class,()->server.acquire(a,"/private",false)).code);
                server.release(a,writer.id());
                server.execute(a,SWITCH_IDENTITY,credentials("alice")); assertEquals(c.home,a.cwd);
            } finally { server.disconnect(a); server.disconnect(b); server.disconnect(c); }
            try(var stopped=new Socket()) { assertNull(server.admit(stopped)); }
        }
        assertArrayEquals(before,Files.readAllBytes(path()));
    }
    @Test void pendingAdmissionsConsumeCapacity() throws Exception {
        try(var fs=create(); var server=new RuntimeServer(fs,UUID.randomUUID())) {
            server.publish(); var pending=new ArrayList<RuntimeServer.Session>();
            try {
                for(int i=0;i<32;i++) pending.add(Objects.requireNonNull(server.admit(new Socket())));
                try(var overflow=new Socket()) { assertNull(server.admit(overflow)); }
            } finally { pending.forEach(server::disconnect); }
            try(var stopped=new Socket()) { assertNull(server.admit(stopped)); }
        }
    }

    @Test void lastDisconnectAndAdmissionHaveOneConsistentWinner() throws Exception {
        try(var fs=create(); var server=new RuntimeServer(fs,UUID.randomUUID());
                var pool=Executors.newVirtualThreadPerTaskExecutor(); var socket=new Socket()) {
            server.publish();
            var original=server.admit(new Socket());
            var start=new CyclicBarrier(2);
            var arriving=pool.submit(()->{ start.await(3,TimeUnit.SECONDS); return server.admit(socket); });
            start.await(3,TimeUnit.SECONDS);
            server.disconnect(original);
            var next=arriving.get(5,TimeUnit.SECONDS);
            if(next!=null) {
                try { assertEquals(RuntimeServer.State.CONNECTED,next.state); }
                finally { server.disconnect(next); }
            }
            try(var stopped=new Socket()) { assertNull(server.admit(stopped)); }
        }
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void concurrentChangeDuringUnlockedPreparationPreventsPublication(boolean disconnect) throws Exception {
        try(var ignored=create()) {}
        var armed=new AtomicBoolean(); var paused=new CountDownLatch(1); var resume=new CountDownLatch(1);
        try(var fs=OperationalFileSystem.mount(path(),step->{
            if(step==IndexedStorage.Step.CREDENTIAL_SNAPSHOT && armed.compareAndSet(true,false)) {
                paused.countDown();
                try { if(!resume.await(8,TimeUnit.SECONDS)) throw new IOException("Test deadline"); }
                catch(InterruptedException e) { throw new IOException(e); }
            }
        });var server=new RuntimeServer(fs,UUID.randomUUID());var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            server.publish();
            var root=session(server,"root"); var pending=server.admit(new Socket());
            pending.state=RuntimeServer.State.UNAUTHENTICATED; armed.set(true);
            try {
                var authentication=pool.submit(()->server.execute(pending,LOGIN,credentials("root")));
                assertTrue(paused.await(5,TimeUnit.SECONDS));
                assertEquals(29,pool.submit(()->server.execute(root,GET_SESSION,ByteBuffer.allocate(0))).get(3,TimeUnit.SECONDS).length);
                if(disconnect) server.disconnect(pending);
                else fs.changePassword(root.actor,"root",pass,new char[]{'x'},new char[]{'x'});
                resume.countDown();
                var failure=assertThrows(ExecutionException.class,()->authentication.get(5,TimeUnit.SECONDS));
                assertEquals(disconnect ? RUNTIME_STOPPING : AUTH_FAILED,
                        assertInstanceOf(RuntimeClient.Failure.class,failure.getCause()).code);
                assertNull(pending.actor);
            } finally { resume.countDown(); server.disconnect(root); server.disconnect(pending); }
        }
    }
    @Test void absoluteHandshakeDeadlineDespitePartialHeader() throws Exception {
        try(var listener=new ServerSocket(0,1,Discovery.LOOPBACK);
            var client=new Socket(Discovery.LOOPBACK,listener.getLocalPort()); var accepted=listener.accept();
            var d=new Discovery(path(),UUID.randomUUID(),UUID.randomUUID(),1,listener.getLocalPort(),nonce());
            var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            client.getOutputStream().write(0x4f);
            var stalled=pool.submit(()->handshake(accepted,d,false,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(200)));
            assertInstanceOf(IOException.class,assertThrows(ExecutionException.class,()->stalled.get(3,TimeUnit.SECONDS)).getCause());
            assertTrue(accepted.isClosed());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void formatFailureResultUsesActualRollbackOutcome(boolean failRollback) throws Exception {
        var failure=assertThrows(IOException.class,()->OperationalFileSystem.format(path(),
                new DiskGeometry(1<<20,512),"v",pass,pass,step->{
                    if(step==IndexedStorage.Step.FLUSH || failRollback && step==IndexedStorage.Step.RESTORE_FLUSH)
                        throw new IOException("Injected");
                }));
        assertEquals(failRollback ? 3 : 2,RuntimeMain.formatFailure(failure));
        try(var disk=VirtualDisk.open(path())) { assertFalse(disk.formatted()); }
    }

    @Test void directoryExecuteHomeFallbackAndFailedSwitch() throws Exception {
        int home;
        try(var fs=create()) {
            var root=fs.authenticate("root",pass);
            home=fs.createUser(root,"alice","Alice",null,pass,pass).home();
        }
        for(boolean rootAccessible:new boolean[]{true,false}) {
            try(var disk=VirtualDisk.open(path())) {
                int block=disk.geometry().fcbStart();
                byte[] page=disk.readBlock(block);
                var b=ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);
                b.putShort(2,(short)(rootAccessible ? 071 : 070));
                b.putShort(2*128+2,(short)070);
                disk.writeBlock(block,page);
            }
            try(var fs=OperationalFileSystem.mount(path()); var server=new RuntimeServer(fs,UUID.randomUUID())) {
                server.publish();
                var s=session(server,"root");
                try {
                    if(rootAccessible) {
                        server.execute(s,SWITCH_IDENTITY,credentials("alice"));
                        assertEquals(home,s.home); assertEquals(0,s.cwd);
                        assertThrows(SecurityException.class,()->fs.list(s.actor,s.cwd,"/"));
                        assertThrows(SecurityException.class,()->fs.directory(s.actor,home,"."));
                        assertEquals(0,fs.directory(s.actor,home,"/"));
                    } else {
                        var actor=s.actor;
                        assertEquals(ACCESS_DENIED,assertThrows(RuntimeClient.Failure.class,
                                ()->server.execute(s,SWITCH_IDENTITY,credentials("alice"))).code);
                        assertSame(actor,s.actor); assertEquals(1,s.home); assertEquals(1,s.cwd);
                    }
                } finally { server.disconnect(s); }
            }
        }
    }

    @Test void disconnectDuringTransactionAndTypedFaultShutdown() throws Exception {
        try(var ignored=create()) {}
        for(boolean fault:new boolean[]{false,true}) {
            var attempted=new CountDownLatch(1); var resume=new CountDownLatch(1);
            var injected=new IOException("test commit");
            try(var fs=OperationalFileSystem.mount(path(),step->{
                if(step==IndexedStorage.Step.WRITE) {
                    attempted.countDown();
                    try { if(!resume.await(5,TimeUnit.SECONDS)) throw new IOException("Test deadline"); }
                    catch(InterruptedException e) { throw new IOException(e); }
                }
                if(fault && step==IndexedStorage.Step.FLUSH) throw injected;
                if(fault && step==IndexedStorage.Step.RESTORE_FLUSH) throw new IOException("test rollback");
            }); var server=new RuntimeServer(fs,UUID.randomUUID()); var pool=Executors.newVirtualThreadPerTaskExecutor()) {
                server.publish();
                var caller=session(server,"root");
                try {
                    var changing=pool.submit(()->server.coordinate(caller,()->
                            fs.create(caller.actor,0,fault?"/fault":"/committed",Fcb.Type.FILE)));
                    assertTrue(attempted.await(5,TimeUnit.SECONDS));
                    caller.socket.close(); // Transport loss does not cancel storage work.
                    var disconnected=pool.submit(()->server.disconnect(caller));
                    resume.countDown();
                    if(fault) {
                        assertSame(injected,assertThrows(ExecutionException.class,()->changing.get(5,TimeUnit.SECONDS)).getCause());
                        assertTrue(fs.faulted());
                        assertTrue(injected.getSuppressed().length > 0);
                        var rejected = assertThrows(RuntimeClient.Failure.class,
                                () -> server.execute(caller,GET_SESSION,ByteBuffer.allocate(0)));
                        assertEquals(STORAGE_FAULTED,rejected.code);
                        assertEquals(0,rejected.outcome);
                        var frame = new Frame(GET_SESSION,9,EMPTY);
                        var closedTransport = new OutputStream() {
                            public void write(int value) { fail("Write after fault shutdown"); }
                        };
                        assertFalse(server.respond(caller,frame,closedTransport));
                        assertEquals(RuntimeServer.Life.FAULTED,server.snapshot().lifecycle());
                        try(var socket=new Socket()) { assertNull(server.admit(socket)); }
                    } else assertEquals(Fcb.Type.FILE,changing.get(5,TimeUnit.SECONDS).type());
                    disconnected.get(5,TimeUnit.SECONDS);
                } finally { resume.countDown(); server.disconnect(caller); }
            }
            try(var fs=OperationalFileSystem.mount(path())) {
                assertEquals(1,fs.list(fs.authenticate("root",pass),0,"/").stream().filter(e->e.name().equals("committed")).count());
            }
        }
    }
}
