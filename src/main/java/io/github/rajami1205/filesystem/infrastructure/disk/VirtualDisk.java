package io.github.rajami1205.filesystem.infrastructure.disk;

import io.github.rajami1205.filesystem.model.disk.AllocationBitmap;
import io.github.rajami1205.filesystem.model.disk.DiskGeometry;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Exclusive structural image access. Closing the owning channel releases its whole-file lock. */
public final class VirtualDisk implements AutoCloseable {
    // Closing a second channel to the same file can release process locks on some hosts.
    private static final Set<VirtualDisk> OPEN = new HashSet<>();
    private record PhysicalIdentity(FileSystem filesystem, Object key) {
        static PhysicalIdentity read(Path path) throws IOException {
            return new PhysicalIdentity(path.getFileSystem(),
                    Files.readAttributes(path, BasicFileAttributes.class).fileKey());
        }
    }

    private final PhysicalIdentity identity;
    private final FileChannel channel;
    private final DiskGeometry geometry;

    public record Owner(Path path, UUID uuid) {}
    private final Owner owner;

    public Owner owner() { return owner; }

    private VirtualDisk(PhysicalIdentity identity, FileChannel channel, DiskGeometry geometry, Owner owner) {
        this.owner = owner;
        this.identity = identity;
        this.channel = channel;
        this.geometry = geometry;
    }

    public static synchronized VirtualDisk create(Path path, DiskGeometry geometry, String volume) throws IOException {
        var header = new DiskHeaderCodec.Header(UUID.randomUUID(), volume, System.currentTimeMillis(), false);
        byte[] superblock = DiskHeaderCodec.superblock(geometry, header);
        byte[] mbr = DiskHeaderCodec.mbr(geometry, header.uuid());
        Path absolute = path.toAbsolutePath();
        FileChannel ch = FileChannel.open(absolute, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            lock(ch);
            // CREATE_NEW establishes a distinct file even on providers without file keys.
            PhysicalIdentity identity = PhysicalIdentity.read(absolute);
            transfer(ch, geometry.diskBytes() - 1, ByteBuffer.allocate(1), true);
            transfer(ch, 0, ByteBuffer.wrap(mbr), true);
            transfer(ch, geometry.offset(1), ByteBuffer.wrap(superblock), true);
            transfer(ch, geometry.offset(DiskGeometry.BITMAP_START),
                    ByteBuffer.wrap(AllocationBitmap.initial(geometry).bytes()), true);
            var zero = ByteBuffer.allocate(geometry.blockSize());
            for (int block = geometry.userStart(); block < geometry.firstAllocatable(); block++) {
                transfer(ch, geometry.offset(block), zero.clear(), true);
            }
            if (ch.size() != geometry.diskBytes()) throw new IOException("Unexpected image length");
            ch.force(true);
            if (!identity.equals(PhysicalIdentity.read(absolute))) throw new IOException("Image changed during creation");
            VirtualDisk disk = new VirtualDisk(identity, ch, geometry, new Owner(absolute.toRealPath(), header.uuid()));
            OPEN.add(disk);
            return disk;
        } catch (IOException | RuntimeException | Error failure) {
            // A path may have been replaced since CREATE_NEW; never unlink by name here.
            closeAfterFailure(ch, failure);
            throw failure;
        }
    }

    public static synchronized VirtualDisk open(Path path) throws IOException {
        Path real = path.toRealPath();
        // The host namespace must remain stable during acquisition. Java NIO does not
        // atomically bind path attributes to an opened channel.
        PhysicalIdentity identity = PhysicalIdentity.read(real);
        for (VirtualDisk held : OPEN) {
            if (identity.filesystem() != held.identity.filesystem()
                    || identity.key() == null || held.identity.key() == null)
                throw new IOException("Cannot distinguish image from an active owner");
            if (identity.equals(held.identity)) throw new IOException("Image already open");
        }
        FileChannel ch = FileChannel.open(real, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            lock(ch);
            if (!identity.equals(PhysicalIdentity.read(real))) throw new IOException("Image changed during open");
            byte[] sector = new byte[DiskGeometry.SECTOR_BYTES];
            transfer(ch, 0, ByteBuffer.wrap(sector), false);
            DiskGeometry geometry = DiskHeaderCodec.discover(sector, ch.size());
            var mbr = ByteBuffer.allocate(geometry.blockSize());
            var superblock = ByteBuffer.allocate(geometry.blockSize());
            transfer(ch, 0, mbr, false);
            transfer(ch, geometry.offset(1), superblock, false);
            var header = DiskHeaderCodec.validate(geometry, mbr.array(), superblock.array());
            VirtualDisk disk = new VirtualDisk(identity, ch, geometry, new Owner(real, header.uuid()));
            disk.readBitmap();
            OPEN.add(disk);
            return disk;
        } catch (IOException | RuntimeException | Error failure) {
            closeAfterFailure(ch, failure);
            throw failure;
        }
    }

    private static void lock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Image already locked");
        } catch (OverlappingFileLockException ex) {
            throw new IOException("Image already locked", ex);
        }
    }

    private static void closeAfterFailure(FileChannel channel, Throwable failure) {
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    public DiskGeometry geometry() { return geometry; }

    public boolean formatted() throws IOException {
        var header = DiskHeaderCodec.validate(geometry, readBlock(0), readBlock(1));
        if (!owner.uuid().equals(header.uuid())) throw new IOException("Disk UUID changed under ownership");
        return header.formatted();
    }

    /** Prepare only a private staged image; the format authority controls validation and persistence. */
    public static void prepareSuperblockPublication(byte[] candidate) {
        DiskHeaderCodec.preparePublication(candidate);
    }

    public void flush() throws IOException { channel.force(false); }

    public byte[] readBlock(int block) throws IOException {
        long offset = geometry.offset(block);
        byte[] bytes = new byte[geometry.blockSize()];
        transfer(channel, offset, ByteBuffer.wrap(bytes), false);
        return bytes;
    }

    public void writeBlock(int block, byte[] bytes) throws IOException {
        long offset = geometry.offset(block);
        if (bytes.length != geometry.blockSize()) throw new IllegalArgumentException("Expected a complete block");
        transfer(channel, offset, ByteBuffer.wrap(bytes), true);
    }

    public AllocationBitmap readBitmap() throws IOException {
        byte[] bytes = new byte[Math.toIntExact(geometry.bitmapRegionBytes())];
        transfer(channel, geometry.offset(DiskGeometry.BITMAP_START), ByteBuffer.wrap(bytes), false);
        try {
            return AllocationBitmap.decode(geometry, bytes);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid allocation bitmap", ex);
        }
    }

    private static void transfer(FileChannel channel, long position, ByteBuffer buffer, boolean write) throws IOException {
        while (buffer.hasRemaining()) {
            int n = write ? channel.write(buffer, position) : channel.read(buffer, position);
            if (n < 0) throw new EOFException("Truncated image");
            if (n == 0) throw new IOException("File transfer made no progress");
            position += n;
        }
    }

    @Override public void close() throws IOException {
        synchronized (VirtualDisk.class) {
            try {
                channel.close();
            } finally {
                if (!channel.isOpen()) OPEN.remove(this);
            }
        }
    }
}
