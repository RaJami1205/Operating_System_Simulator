package io.github.rajami1205.filesystem.application;

import java.io.*;
import java.net.Socket;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntPredicate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** OSIP v1 and shared bounded binary fields. All numbers are big endian. */
final class RuntimeWire {
    static final int HELLO=1, CHALLENGE=2, PROOF=3, OK=4, LOGIN=10, SESSION=11,
            GET_SESSION=12, SET_CWD=13, SWITCH_IDENTITY=14, EXIT=15, ERROR=255;
    static final int AUTH_FAILED=1, ACCESS_DENIED=2, INVALID_PROTOCOL=3, INVALID_REQUEST=4,
            CONNECTION_LIMIT=5, FILE_IN_USE=6, RUNTIME_UNAVAILABLE=7, RUNTIME_STOPPING=8,
            STORAGE_FAULTED=9, NOT_FOUND=10, INVALID_TYPE=11;
    static final SecureRandom RANDOM = new SecureRandom();
    static final byte[] EMPTY = {};
    static final class Protocol extends IOException {
        Protocol() { super("Invalid runtime protocol"); }
    }
    record Frame(int type, long id, byte[] data) implements AutoCloseable {
        ByteBuffer payload() { return ByteBuffer.wrap(data); }
        public void close() { Arrays.fill(data, (byte)0); }
    }
    private static final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1,
            Thread.ofPlatform().daemon().name("runtime-deadline").factory());
    static { timers.setRemoveOnCancelPolicy(true); }
    static ScheduledFuture<?> until(long deadline, Runnable task) {
        return timers.schedule(task, Math.max(0, deadline-System.nanoTime()), TimeUnit.NANOSECONDS);
    }
    static long deadline(int seconds) { return System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds); }
    static void close(Closeable c) { try { c.close(); } catch (IOException ignored) {} }

    static void size(int type, int n) throws Protocol {
        boolean valid = switch (type) {
            case HELLO, CHALLENGE -> n == 64;
            case PROOF -> n == 32;
            case OK, GET_SESSION, EXIT -> n == 0;
            case LOGIN, SWITCH_IDENTITY -> n >= 4 && n <= 548;
            case SESSION -> n == 29;
            case SET_CWD -> n >= 2 && n <= 4098;
            case ERROR -> n == 3;
            default -> false;
        };
        if (n < 0 || n > 8*1024*1024 || !valid) throw new Protocol();
    }
    static Frame read(InputStream stream, long previous, long expected, IntPredicate allowed) throws IOException {
        var in = new DataInputStream(stream);
        if (in.readInt()!=0x4f534950 || in.readUnsignedShort()!=1) throw new Protocol();
        int type=in.readUnsignedShort();
        long id=in.readLong();
        int n=in.readInt();
        if (id<=previous || (expected!=0 && id!=expected) || !allowed.test(type)) throw new Protocol();
        size(type,n);
        byte[] data=new byte[n];
        try { in.readFully(data); return new Frame(type,id,data); }
        catch (IOException e) { Arrays.fill(data,(byte)0); throw e; }
    }
    static void send(OutputStream stream,int type,long id,byte[] data) throws IOException {
        size(type,data.length);
        if (id<=0) throw new Protocol();
        var out=new DataOutputStream(stream);
        out.writeInt(0x4f534950); out.writeShort(1); out.writeShort(type); out.writeLong(id);
        out.writeInt(data.length); out.write(data); out.flush();
    }
    static void uuid(ByteBuffer b,UUID u) { b.putLong(u.getMostSignificantBits()).putLong(u.getLeastSignificantBits()); }
    static UUID uuid(ByteBuffer b) { return new UUID(b.getLong(),b.getLong()); }
    static void end(ByteBuffer b) throws Protocol { if (b.hasRemaining()) throw new Protocol(); }
    static void putText(ByteBuffer b,CharBuffer value,int max) throws IOException {
        if(value.remaining()>max) throw new Protocol();
        var raw=StandardCharsets.UTF_8.newEncoder().encode(value);
        try {
            if (raw.remaining()>max) throw new Protocol();
            b.putShort((short)raw.remaining()).put(raw);
        } finally { Arrays.fill(raw.array(),(byte)0); }
    }
    static char[] chars(ByteBuffer b,int max) throws IOException {
        if (b.remaining()<2) throw new Protocol();
        int n=Short.toUnsignedInt(b.getShort());
        if (n>max || n>b.remaining()) throw new Protocol();
        var decoded=StandardCharsets.UTF_8.newDecoder().decode(b.slice(b.position(),n));
        b.position(b.position()+n);
        try { char[] result=new char[decoded.remaining()]; decoded.get(result); return result; }
        finally { Arrays.fill(decoded.array(),'\0'); }
    }
    static String text(ByteBuffer b,int max) throws IOException { return new String(chars(b,max)); }
    static byte[] used(ByteBuffer b) { return Arrays.copyOf(b.array(),b.position()); }
    static byte[] nonce() { byte[] n=new byte[32]; RANDOM.nextBytes(n); return n; }
    static byte[] proof(byte role,Discovery d,byte[] client,byte[] server) {
        var b=ByteBuffer.allocate(106);
        b.put(role).put("OSIP-HS".getBytes(StandardCharsets.US_ASCII)).putShort((short)1);
        uuid(b,d.disk()); uuid(b,d.instance()); b.put(client).put(server);
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(d.secret(),"HmacSHA256"));
            return mac.doFinal(b.array());
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static final class HandshakeEnd {
        private final Socket socket;
        private final long limit;
        private boolean pending = true;

        HandshakeEnd(Socket socket, long limit) { this.socket = socket; this.limit = limit; }

        void expire() {
            synchronized (this) {
                if (!pending) return;
                pending = false;
            }
            close(socket);
        }

        synchronized long finish(long id) throws java.net.SocketTimeoutException {
            if (!pending || System.nanoTime() >= limit) throw new java.net.SocketTimeoutException();
            pending = false;
            return id;
        }
    }

    static long handshake(Socket socket,Discovery d,boolean client,long deadline) throws IOException {
        long limit=Math.min(deadline,deadline(10));
        return handshake(socket,d,client,limit,new HandshakeEnd(socket,limit));
    }

    static long handshake(Socket socket,Discovery d,boolean client,long limit,HandshakeEnd end) throws IOException {
        var timer=until(limit,end::expire);
        byte[] cn=nonce(), sn=nonce(), received=new byte[32];
        try {
            var in=socket.getInputStream(); var out=socket.getOutputStream();
            var hello=ByteBuffer.allocate(64);
            uuid(hello,d.disk()); uuid(hello,d.instance());
            long first=1;
            if (client) {
                send(out,HELLO,first,hello.put(cn).array());
                try(var f=read(in,0,first,t->t==CHALLENGE || t==ERROR)) {
                    var payload=f.payload();
                    if(f.type()==ERROR) {
                        int code=Short.toUnsignedInt(payload.getShort());
                        if((code!=CONNECTION_LIMIT && code!=RUNTIME_STOPPING) || payload.get()!=0) throw new Protocol();
                        throw new RuntimeClient.Failure(code,0);
                    }
                    payload.get(sn).get(received);
                }
                verify((byte)0x53,d,cn,sn,received);
                byte[] proof=proof((byte)0x43,d,cn,sn);
                try { send(out,PROOF,2,proof); } finally { Arrays.fill(proof,(byte)0); }
                try(var ignored=read(in,1,2,t->t==OK)) {
                    return end.finish(2);
                }
            }
            try(var f=read(in,0,0,t->t==HELLO)) {
                var b=f.payload();
                if (!uuid(b).equals(d.disk()) || !uuid(b).equals(d.instance())) throw new Protocol();
                b.get(cn); first=f.id();
            }
            byte[] proof=proof((byte)0x53,d,cn,sn);
            var challenge=ByteBuffer.allocate(64).put(sn).put(proof);
            try { send(out,CHALLENGE,first,challenge.array()); }
            finally { Arrays.fill(challenge.array(),(byte)0); Arrays.fill(proof,(byte)0); }
            try(var f=read(in,first,0,t->t==PROOF)) {
                verify((byte)0x43,d,cn,sn,f.data());
                if(System.nanoTime()>=limit) throw new java.net.SocketTimeoutException();
                send(out,OK,f.id(),EMPTY);
                return end.finish(f.id());
            }
        } finally {
            end.expire();
            timer.cancel(false); Arrays.fill(cn,(byte)0); Arrays.fill(sn,(byte)0); Arrays.fill(received,(byte)0);
        }
    }
    private static void verify(byte role,Discovery d,byte[] cn,byte[] sn,byte[] received) throws Protocol {
        byte[] expected=proof(role,d,cn,sn);
        try { if (!MessageDigest.isEqual(expected,received)) throw new Protocol(); }
        finally { Arrays.fill(expected,(byte)0); }
    }
}
