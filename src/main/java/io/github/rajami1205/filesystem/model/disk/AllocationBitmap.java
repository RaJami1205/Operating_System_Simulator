package io.github.rajami1205.filesystem.model.disk;

import java.util.Arrays;
import java.util.BitSet;
import java.util.ArrayList;
import java.util.List;

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

    /** Copy one encoded bitmap page directly into a staged physical block. */
    public void copyPage(int page, byte[] target) {
        if (target.length != geometry.blockSize() || page < 0 || page >= geometry.bitmapBlocks())
            throw new IllegalArgumentException("Invalid bitmap page");
        System.arraycopy(bytes, page * target.length, target, 0, target.length);
    }

    public record Reservation(AllocationBitmap bitmap, List<Integer> blocks) {}

    public Reservation allocate(int count) {
        if (count < 0 || count > geometry.allocatableBlocks())
            throw new IllegalArgumentException("Invalid allocation size");
        if (count == 0) return new Reservation(this, List.of());
        List<Integer> selected = new ArrayList<>();
        byte[] candidate = bytes.clone();
        for (int id = geometry.firstAllocatable(); id < geometry.totalBlocks(); id++) {
            if (occupied(id)) continue;
            selected.add(id);
            candidate[id / 8] |= (byte) (1 << (id % 8));
            if (selected.size() == count)
                return new Reservation(new AllocationBitmap(geometry, candidate), List.copyOf(selected));
        }
        throw new IllegalArgumentException("Insufficient free blocks");
    }

    public AllocationBitmap release(List<Integer> ids) {
        if (ids.isEmpty()) return this;
        BitSet releasing = new BitSet();
        for (int id : ids) {
            if (id < geometry.firstAllocatable() || !occupied(id) || releasing.get(id))
                throw new IllegalArgumentException("Invalid block release: " + id);
            releasing.set(id);
        }
        byte[] candidate = bytes.clone();
        for (int id : ids) candidate[id / 8] &= (byte) ~(1 << (id % 8));
        return new AllocationBitmap(geometry, candidate);
    }
}
