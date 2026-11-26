package io.github.rajami1205.filesystem.application;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static io.github.rajami1205.filesystem.application.RuntimeWire.*;

record Discovery(Path path, UUID disk, UUID instance, long pid, int port, byte[] secret) implements AutoCloseable {
    static final InetAddress LOOPBACK = InetAddress.ofLiteral("127.0.0.1");
    private static final int MAX=65536;
    Path sidecar() { return sidecar(path); }
    static Path sidecar(Path path) { return path.resolveSibling(path.getFileName()+".runtime"); }
    @Override public String toString() { return "Runtime instance "+instance; }
    public void close() { Arrays.fill(secret,(byte)0); }

    byte[] encode() throws IOException {
        var b=ByteBuffer.allocate(MAX);
        try {
            b.putInt(0x4f535254).putShort((short)1).putShort((short)1);
            putText(b,CharBuffer.wrap(path.toString()),MAX-88);
            uuid(b,disk); uuid(b,instance); b.putLong(pid).putInt(0x7f000001).putShort((short)port).put(secret);
            return used(b);
        } catch(BufferOverflowException e) { throw new Protocol(); }
        finally { Arrays.fill(b.array(),(byte)0); }
    }
    static Discovery decode(Path path,byte[] data) throws IOException {
        var b=ByteBuffer.wrap(data);
        if(data.length>MAX || data.length<88 || b.getInt()!=0x4f535254 || b.getShort()!=1 || b.getShort()!=1
                || !text(b,65535).equals(path.toString()) || b.remaining()!=78) throw new Protocol();
        UUID disk=uuid(b), instance=uuid(b); long pid=b.getLong();
        if(pid<=0 || b.getInt()!=0x7f000001) throw new Protocol();
        int port=Short.toUnsignedInt(b.getShort());
        if(port==0) throw new Protocol();
        byte[] key=new byte[32]; b.get(key);
        return new Discovery(path,disk,instance,pid,port,key);
    }
    static Discovery read(Path disk) throws IOException {
        return readAt(disk,sidecar(disk));
    }
    private static Discovery readAt(Path disk,Path p) throws IOException {
        verifyProtection(p);
        try(var in=Files.newInputStream(p,NOFOLLOW_LINKS)) {
            byte[] bytes=in.readNBytes(MAX+1);
            try { return decode(disk,bytes); } finally { Arrays.fill(bytes,(byte)0); }
        }
    }
    private static UserPrincipal current(Path p) throws IOException {
        return p.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
    }
    private static FileAttribute<?> protection(Path parent) throws IOException {
        if(Files.getFileAttributeView(parent,PosixFileAttributeView.class)!=null)
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
        var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(current(parent))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        return new FileAttribute<List<AclEntry>>() {
            public String name() { return "acl:acl"; }
            public List<AclEntry> value() { return List.of(entry); }
        };
    }
    private static void verifyProtection(Path p) throws IOException {
        var attrs=Files.readAttributes(p,BasicFileAttributes.class,NOFOLLOW_LINKS);
        if(!attrs.isRegularFile() || attrs.isSymbolicLink() || !Files.getOwner(p,NOFOLLOW_LINKS).equals(current(p)))
            throw new IOException("Unsafe discovery file");
        var posix=Files.getFileAttributeView(p,PosixFileAttributeView.class,NOFOLLOW_LINKS);
        if(posix!=null) {
            if(!posix.readAttributes().permissions().equals(PosixFilePermissions.fromString("rw-------")))
                throw new IOException("Unsafe discovery permissions");
        } else {
            var acl=Files.getFileAttributeView(p,AclFileAttributeView.class,NOFOLLOW_LINKS);
            if(acl==null) throw new IOException("Discovery ACL unavailable");
            var entries=acl.getAcl();
            var owner=acl.getOwner();
            if(entries.isEmpty() || entries.stream().anyMatch(e->e.type()!=AclEntryType.ALLOW || !e.principal().equals(owner)))
                throw new IOException("Unsafe discovery ACL");
        }
    }
    void publish() throws IOException {
        Path p=sidecar();
        if(Files.isSymbolicLink(p)) throw new IOException("Redirected discovery");
        Path tmp=p.resolveSibling(".osrt-"+UUID.randomUUID()+".tmp");
        var options=Set.of(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        Object fileKey=null;
        boolean created=false;
        try {
            try(var channel=FileChannel.open(tmp,options,protection(p.getParent()))) {
                created=true;
                fileKey=Files.readAttributes(tmp,BasicFileAttributes.class,NOFOLLOW_LINKS).fileKey();
                verifyProtection(tmp);
                byte[] bytes=encode();
                try {
                    var buffer=ByteBuffer.wrap(bytes);
                    while(buffer.hasRemaining()) channel.write(buffer);
                } finally { Arrays.fill(bytes,(byte)0); }
            }
            if(!matches(tmp)) throw new IOException("Discovery temporary changed");
            Files.move(tmp,p,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } catch(IOException | RuntimeException failure) {
            if(created) {
                try {
                    var attrs=Files.readAttributes(tmp,BasicFileAttributes.class,NOFOLLOW_LINKS);
                    if((fileKey!=null && fileKey.equals(attrs.fileKey())) || matches(tmp)) Files.delete(tmp);
                } catch(NoSuchFileException ignored) {
                } catch(IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }
    private boolean matches(Path file) throws IOException {
        try(var record=readAt(path,file)) {
            return instance.equals(record.instance) && disk.equals(record.disk)
                    && java.security.MessageDigest.isEqual(secret,record.secret);
        }
    }
    void removeOwn() throws IOException {
        try(var found=read(path)) {
            if(found.instance.equals(instance) && found.disk.equals(disk)) Files.delete(sidecar());
        } catch(NoSuchFileException ignored) {}
    }
}
