package io.github.rajami1205.filesystem.application;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.rajami1205.filesystem.application.RuntimeWire.*;

class RuntimeWireTest {
    final HexFormat hex=HexFormat.of();
    final UUID disk=UUID.fromString("00010203-0405-0607-0809-0a0b0c0d0e0f");
    final UUID instance=UUID.fromString("10111213-1415-1617-1819-1a1b1c1d1e1f");
    @Test void goldenFramesAndFragmentation() throws Exception {
        byte[] golden=hex.parseHex("4f5349500001000d0000000000000007000000040002c3a9");
        var out=new ByteArrayOutputStream();
        send(out,SET_CWD,7,hex.parseHex("0002c3a9"));
        assertArrayEquals(golden,out.toByteArray());
        var fragmented=new ByteArrayInputStream(golden) {
            public synchronized int read(byte[] b,int p,int n) { return super.read(b,p,Math.min(n,1)); }
        };
        try(var f=read(fragmented,6,7,t->t==SET_CWD)) { assertEquals("é",text(f.payload(),4096)); }
        for(int i=0;i<golden.length;i++) {
            var truncated=new ByteArrayInputStream(Arrays.copyOf(golden,i));
            assertThrows(IOException.class,()->read(truncated,0,0,t->true));
        }
        for(int offset:new int[]{0,5,7,15,16,19}) {
            byte[] bad=golden.clone(); bad[offset]=0;
            if(offset==16) bad[offset]=(byte)128;
            assertThrows(Protocol.class,()->read(new ByteArrayInputStream(bad),0,0,t->true));
        }
        assertThrows(Protocol.class,()->read(new ByteArrayInputStream(golden),7,0,t->true));
        assertThrows(Protocol.class,()->read(new ByteArrayInputStream(golden),0,8,t->true));
        assertThrows(Protocol.class,()->read(new ByteArrayInputStream(golden),0,0,t->t==LOGIN));
        assertThrows(IOException.class,()->chars(ByteBuffer.wrap(hex.parseHex("0001ff")),512));
        assertThrows(Protocol.class,()->end(ByteBuffer.allocate(1)));
    }
    @Test void independentProofsAndBindings() {
        byte[] secret=new byte[32],cn=new byte[32],sn=new byte[32];
        for(int i=0;i<32;i++) secret[i]=(byte)i;
        Arrays.fill(cn,(byte)1); Arrays.fill(sn,(byte)2);
        try(var d=new Discovery(Path.of("v"),disk,instance,1,1,secret)) {
            byte[] server=proof((byte)0x53,d,cn,sn);
            assertEquals("2be1034a69c419029ebd7060950a8b8b10a794418bf0599312d7c76d9c582265",hex.formatHex(server));
            assertEquals("4481fede78b4f1eae0e7153b186b024a76550bdddc19c3e49b656667d87eeed4",hex.formatHex(proof((byte)0x43,d,cn,sn)));
            sn[0]++;
            assertFalse(Arrays.equals(server,proof((byte)0x53,d,cn,sn)));
        }
    }
    @Test void discoveryAndPrivatePipeGoldenBytes() throws Exception {
        String ids="000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";
        var path=Path.of("v");
        byte[] record=hex.parseHex("4f53525400010001000176"+ids+"00000000000000017f0000011234"+"00".repeat(32));
        try(var d=Discovery.decode(path,record)) {
            assertEquals(instance,d.instance()); assertEquals(0x1234,d.port());
            assertArrayEquals(record,d.encode());
        }
        assertThrows(IOException.class,()->Discovery.decode(path,Arrays.copyOf(record,record.length+1)));
        byte[] bootstrap=hex.parseHex("4f5346420001000000000010000000000200000176000170000170");
        var out=new ByteArrayOutputStream();
        try(var input=Bootstrap.read(new ByteArrayInputStream(bootstrap))) {
            assertEquals(1<<20,input.geometry().diskBytes()); assertArrayEquals(new char[]{'p'},input.password());
            input.write(out); assertArrayEquals(bootstrap,out.toByteArray());
        }
        assertThrows(IOException.class,()->Bootstrap.read(new ByteArrayInputStream(new byte[4097])));
        assertThrows(IOException.class,()->Bootstrap.read(new ByteArrayInputStream(Arrays.copyOf(bootstrap,bootstrap.length+1))));
        out.reset();
        new Bootstrap.Result(0,disk,instance).write(out);
        assertArrayEquals(hex.parseHex("4f534652000100"+ids),out.toByteArray());
        assertEquals(disk,Bootstrap.Result.read(new ByteArrayInputStream(out.toByteArray())).disk());
        for(int code=1;code<=3;code++) {
            var result=new Bootstrap.Result(code,Bootstrap.ZERO,Bootstrap.ZERO);
            out.reset(); result.write(out);
            assertEquals(result,Bootstrap.Result.read(new ByteArrayInputStream(out.toByteArray())));
        }
        var lost=assertThrows(RuntimeClient.StartupFailure.class,()->
                RuntimeClient.acknowledgement(new ByteArrayInputStream(Arrays.copyOf(out.toByteArray(),8))));
        assertEquals(3,lost.result);
        assertEquals("OUTCOME_UNKNOWN",lost.getMessage());
    }
    @Test void protectedAtomicReplacementAndSelectiveCleanup(@TempDir Path dir) throws Exception {
        Path path=dir.toRealPath().resolve("v.fs");
        try(var a=new Discovery(path,disk,instance,1,1,nonce());
            var b=new Discovery(path,disk,UUID.randomUUID(),2,2,nonce())) {
            a.publish(); b.publish(); a.removeOwn();
            try(var found=Discovery.read(path)) { assertEquals(b.instance(),found.instance()); }
            b.removeOwn(); assertFalse(Files.exists(a.sidecar()));
        }
    }

    @Test void unsupportedFilesystemDoesNotPublishDiscovery(@TempDir Path dir) throws Exception {
        var uri=java.net.URI.create("jar:"+dir.resolve("unsupported.zip").toUri());
        try(var zip=FileSystems.newFileSystem(uri,Map.of("create","true"));
            var record=new Discovery(zip.getPath("/v.fs"),disk,instance,1,1234,nonce())) {
            var failure=assertThrows(Exception.class,record::publish);
            assertTrue(failure instanceof IOException || failure instanceof UnsupportedOperationException);
            assertFalse(Files.exists(record.sidecar()));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"wrongSecret","wrongInstance","replay","reflection"})
    void peerCannotCompleteInvalidHandshake(String variant) throws Exception {
        try(var listener=new ServerSocket(0,1,Discovery.LOOPBACK);
            var client=new Socket(Discovery.LOOPBACK,listener.getLocalPort()); var server=listener.accept();
            var record=new Discovery(Path.of("v"),disk,instance,1,listener.getLocalPort(),nonce());
            var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var result=pool.submit(()->{ try(server) { return handshake(server,record,false,deadline(3)); } });
            var hello=ByteBuffer.allocate(64);
            byte[] cn=nonce(),sn=new byte[32],response=new byte[32];
            uuid(hello,disk); uuid(hello,variant.equals("wrongInstance") ? disk : instance);
            send(client.getOutputStream(),HELLO,1,hello.put(cn).array());
            if(!variant.equals("wrongInstance")) {
                try(var frame=read(client.getInputStream(),0,1,t->t==CHALLENGE)) {
                    frame.payload().get(sn).get(response);
                }
                if(variant.equals("wrongSecret")) {
                    try(var wrong=new Discovery(record.path(),disk,instance,1,record.port(),nonce())) {
                        response=proof((byte)0x43,wrong,cn,sn);
                    }
                } else if(variant.equals("replay")) {
                    sn[0]^=1; response=proof((byte)0x43,record,cn,sn);
                }
                send(client.getOutputStream(),PROOF,2,response);
            }
            assertInstanceOf(Protocol.class,assertThrows(ExecutionException.class,()->result.get(4,TimeUnit.SECONDS)).getCause());
            assertEquals(-1,client.getInputStream().read());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    void deadlineAndFinalOkHaveOneWinner(boolean expireDuringWrite) throws Exception {
        try(var listener=new ServerSocket(0,1,Discovery.LOOPBACK);
            var client=new Socket(Discovery.LOOPBACK,listener.getLocalPort()); var accepted=listener.accept();
            var record=new Discovery(Path.of("v"),disk,instance,1,listener.getLocalPort(),nonce());
            var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var finalFlush = new CountDownLatch(1);
            var resume = new CountDownLatch(1);
            var output = new FilterOutputStream(accepted.getOutputStream()) {
                int flushes;
                public void flush() throws IOException {
                    if (++flushes == 2) {
                        finalFlush.countDown();
                        try { if (!resume.await(5,TimeUnit.SECONDS)) throw new IOException("Test deadline"); }
                        catch (InterruptedException e) { throw new IOException(e); }
                    }
                    super.flush();
                }
            };
            try (var transport = new Socket() {
                public InputStream getInputStream() throws IOException { return accepted.getInputStream(); }
                public OutputStream getOutputStream() { return output; }
                public void close() throws IOException { accepted.close(); super.close(); }
            }) {
                long limit = deadline(8);
                var end = new HandshakeEnd(transport,limit);
                var server = pool.submit(() -> handshake(transport,record,false,limit,end));
                var peer = pool.submit(() -> handshake(client,record,true,limit));
                try {
                    assertTrue(finalFlush.await(5,TimeUnit.SECONDS));
                    if (expireDuringWrite) end.expire();
                } finally { resume.countDown(); }
                if (expireDuringWrite) {
                    assertInstanceOf(IOException.class,assertThrows(ExecutionException.class,
                            () -> server.get(5,TimeUnit.SECONDS)).getCause());
                    assertTrue(accepted.isClosed());
                } else {
                    assertEquals(2L,server.get(5,TimeUnit.SECONDS));
                    assertEquals(2L,peer.get(5,TimeUnit.SECONDS));
                    end.expire();
                    client.getOutputStream().write(42);
                    accepted.setSoTimeout(2000);
                    assertEquals(42,accepted.getInputStream().read());
                }
            }
        }
    }

    @Test void unverifiedEndpointReceivesOnlyHello() throws Exception {
        try(var listener=new ServerSocket(0,1,Discovery.LOOPBACK);
            var client=new Socket(Discovery.LOOPBACK,listener.getLocalPort()); var server=listener.accept();
            var record=new Discovery(Path.of("v"),disk,instance,1,listener.getLocalPort(),nonce());
            var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var result=pool.submit(()->{ try(client) { return handshake(client,record,true,deadline(3)); } });
            try(var frame=read(server.getInputStream(),0,1,t->t==HELLO)) {
                send(server.getOutputStream(),CHALLENGE,frame.id(),new byte[64]);
            }
            assertInstanceOf(Protocol.class,assertThrows(ExecutionException.class,()->result.get(4,TimeUnit.SECONDS)).getCause());
            assertEquals(-1,server.getInputStream().read());
        }
    }
}
