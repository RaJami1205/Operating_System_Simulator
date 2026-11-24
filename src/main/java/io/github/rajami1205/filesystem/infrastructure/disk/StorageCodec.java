package io.github.rajami1205.filesystem.infrastructure.disk;

import io.github.rajami1205.filesystem.model.disk.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;

public final class StorageCodec {
    private StorageCodec() {}

    private static ByteBuffer buffer(byte[] bytes, int offset, int size) {
        return ByteBuffer.wrap(bytes, offset, size).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    public static Fcb readFcb(byte[] block, int offset, DiskGeometry g, int slot) {
        ByteBuffer b = buffer(block, offset, DiskGeometry.FCB_BYTES);
        int state = b.get();
        if (state == 0) {
            requireZero(b);
            return null;
        }
        int type = b.get();
        if (state != 1 || type < 1 || type > 3) throw new IllegalArgumentException("Unknown FCB state/type");
        Fcb record = new Fcb(Fcb.Type.values()[type - 1], Short.toUnsignedInt(b.getShort()), b.getInt(),
                b.getInt(), b.getInt(), b.getInt(), b.getInt(), b.getLong(), b.getLong(), b.getLong());
        if (b.getLong() != -1) throw new IllegalArgumentException("Invalid access timestamp");
        requireZero(b);
        record.validate(g, slot);
        return record;
    }

    private static void requireZero(ByteBuffer b) {
        while (b.hasRemaining()) if (b.get() != 0) throw new IllegalArgumentException("Nonzero reserved byte");
    }

    public static void writeFcb(byte[] block, int offset, Fcb record) {
        Arrays.fill(block, offset, offset + DiskGeometry.FCB_BYTES, (byte) 0);
        if (record == null) return;
        buffer(block, offset, DiskGeometry.FCB_BYTES).put((byte) 1).put((byte) (record.type().ordinal() + 1))
                .putShort((short) record.mode()).putInt(record.id()).putInt(record.owner()).putInt(record.group())
                .putInt(record.parent()).putInt(record.firstIndex()).putLong(record.size()).putLong(record.created())
                .putLong(record.modified()).putLong(-1);
    }

    private static final int IDX1 = 0x31584449;

    public static IndexBlock readIndex(byte[] bytes, DiskGeometry g) {
        ByteBuffer b = buffer(bytes, 0, g.blockSize());
        if (b.getInt() != IDX1) throw new IllegalArgumentException("Missing IDX1");
        int next = b.getInt();
        int count = b.getInt();
        if (count < 1 || count > IndexBlock.capacity(g) || b.getInt() != 0)
            throw new IllegalArgumentException("Malformed index header");
        var refs = new ArrayList<Integer>(count);
        for (int i = 0; i < count; i++) refs.add(b.getInt());
        while (b.hasRemaining()) if (b.getInt() != -1) throw new IllegalArgumentException("Invalid unused pointer");
        var index = new IndexBlock(next, refs);
        index.validate(g);
        return index;
    }

    public static void writeIndex(byte[] bytes, IndexBlock index, DiskGeometry g) {
        index.validate(g);
        ByteBuffer b = buffer(bytes, 0, g.blockSize());
        b.putInt(IDX1).putInt(index.next()).putInt(index.references().size()).putInt(0);
        for (int id : index.references()) b.putInt(id);
        while (b.hasRemaining()) b.putInt(-1);
    }
}
