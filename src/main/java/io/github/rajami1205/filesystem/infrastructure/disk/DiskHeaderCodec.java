package io.github.rajami1205.filesystem.infrastructure.disk;

import io.github.rajami1205.filesystem.model.disk.DiskGeometry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

final class DiskHeaderCodec {
    private DiskHeaderCodec() {}

    record Header(UUID uuid, String volume, long createdMillis, boolean formatted) {}

    static DiskGeometry discover(byte[] sector, long actualLength) throws IOException {
        var input = ByteBuffer.wrap(sector).order(ByteOrder.LITTLE_ENDIAN);
        long lba = Integer.toUnsignedLong(input.getInt(454));
        if (lba != 1 && lba != 2 && lba != 4 && lba != 8) throw new IOException("Invalid partition start LBA");
        try {
            return new DiskGeometry(actualLength, Math.toIntExact(lba * DiskGeometry.SECTOR_BYTES));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid image length", ex);
        }
    }

    static byte[] mbr(DiskGeometry g, UUID uuid) {
        var b = ByteBuffer.allocate(g.blockSize()).order(ByteOrder.LITTLE_ENDIAN);
        b.put("OSFSIMG1".getBytes(StandardCharsets.US_ASCII)).putInt(1);
        putUuid(b, 12, uuid);
        b.put(446, (byte) 0x80).put(450, (byte) 0xDA);
        b.putInt(454, g.startLba()).putInt(458, g.partitionSectors());
        b.put(510, (byte) 0x55).put(511, (byte) 0xAA);
        return b.array();
    }

    static byte[] superblock(DiskGeometry g, Header header) throws IOException {
        if (header.volume().length() > 64) throw new IllegalArgumentException("Volume name exceeds 64 bytes");
        var name = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(header.volume()));
        if (name.remaining() > 64) throw new IllegalArgumentException("Volume name exceeds 64 bytes");
        var b = ByteBuffer.allocate(g.blockSize()).order(ByteOrder.LITTLE_ENDIAN);
        b.put("OSFSVOL1".getBytes(StandardCharsets.US_ASCII)).putInt(1).putInt(256);
        b.putInt(g.blockSize()).putInt(1).putLong(g.diskBytes());
        b.putInt(g.startLba()).putInt(g.partitionSectors()).putInt(g.totalBlocks()).putInt(g.firstAllocatable());
        b.putInt(DiskGeometry.BITMAP_START).putInt(g.bitmapBlocks());
        b.putInt(g.userStart()).putInt(g.userBlocks()).putInt(DiskGeometry.USER_BYTES).putInt(DiskGeometry.USER_COUNT);
        b.putInt(g.groupStart()).putInt(g.groupBlocks()).putInt(DiskGeometry.GROUP_BYTES).putInt(DiskGeometry.GROUP_COUNT);
        b.putInt(g.fcbStart()).putInt(g.fcbBlocks()).putInt(DiskGeometry.FCB_BYTES).putInt(g.fcbCapacity());
        putUuid(b, 112, header.uuid());
        b.position(128).putShort((short) name.remaining()).put(name);
        b.putLong(200, header.createdMillis()).putInt(208, header.formatted() ? 1 : 0);
        return b.array();
    }

    private static void putUuid(ByteBuffer buffer, int offset, UUID uuid) {
        buffer.duplicate().order(ByteOrder.BIG_ENDIAN).position(offset)
                .putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits());
    }

    static Header validate(DiskGeometry g, byte[] mbr, byte[] superblock) throws IOException {
        var id = ByteBuffer.wrap(mbr).order(ByteOrder.BIG_ENDIAN);
        UUID uuid = new UUID(id.getLong(12), id.getLong(20));
        var b = ByteBuffer.wrap(superblock).order(ByteOrder.LITTLE_ENDIAN);
        int size = Short.toUnsignedInt(b.getShort(128));
        int status = b.getInt(208);
        if (size > 64 || (status != 0 && status != 1)) throw new IOException("Invalid volume name length or status");
        String name = StandardCharsets.UTF_8.newDecoder().decode(b.slice(130, size)).toString();
        Header header = new Header(uuid, name, b.getLong(200), status == 1);
        // Canonical encoding checks every fixed field, descriptor, UUID copy and reserved byte.
        if (!Arrays.equals(mbr, mbr(g, uuid))) throw new IOException("Invalid MBR or partition geometry");
        if (!Arrays.equals(superblock, superblock(g, header))) throw new IOException("Invalid Superblock");
        return header;
    }
}
