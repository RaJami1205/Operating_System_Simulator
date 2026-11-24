package io.github.rajami1205.filesystem.model.disk;

import java.util.Arrays;
import java.util.BitSet;

/** Immutable on-disk bitmap including unavailable padding. BitSet uses D01's LSB-first order. */
public final class AllocationBitmap {
    private final DiskGeometry geometry;
    private final byte[] bytes;

    private AllocationBitmap(DiskGeometry geometry, byte[] bytes) {
        this.geometry = geometry;
        this.bytes = bytes;
    }

    public static AllocationBitmap initial(DiskGeometry geometry) {
        int length = Math.toIntExact(geometry.bitmapRegionBytes());
        BitSet bits = new BitSet(length * 8);
        bits.set(0, geometry.firstAllocatable());
        bits.set(geometry.totalBlocks(), length * 8);
        return new AllocationBitmap(geometry, Arrays.copyOf(bits.toByteArray(), length));
    }

    public static AllocationBitmap decode(DiskGeometry geometry, byte[] source) {
        if (source.length != geometry.bitmapRegionBytes())
            throw new IllegalArgumentException("Invalid bitmap length");
        byte[] bytes = source.clone();
        BitSet bits = BitSet.valueOf(bytes);
        if (bits.nextClearBit(0) < geometry.firstAllocatable()
                || bits.nextClearBit(geometry.totalBlocks()) < bytes.length * 8)
            throw new IllegalArgumentException("Free bit in reserved or unavailable space");
        return new AllocationBitmap(geometry, bytes);
    }

    public boolean occupied(int block) {
        geometry.offset(block);
        return (bytes[block / 8] & (1 << (block % 8))) != 0;
    }

    public byte[] bytes() { return bytes.clone(); }
}
