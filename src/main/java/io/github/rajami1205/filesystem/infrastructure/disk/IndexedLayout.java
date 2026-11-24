package io.github.rajami1205.filesystem.infrastructure.disk;

import io.github.rajami1205.filesystem.model.disk.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/** Physical addressing and read-only traversal on the owning channel. */
public final class IndexedLayout {
    private final VirtualDisk disk;
    private final DiskGeometry g;

    public IndexedLayout(VirtualDisk disk) {
        this.disk = disk;
        g = disk.geometry();
    }

    private long slotOffset(int slot) {
        if (slot < 0 || slot >= g.fcbCapacity()) throw new IllegalArgumentException("Invalid slot");
        return (long) slot * DiskGeometry.FCB_BYTES;
    }

    public int tableBlock(int slot) { return g.fcbStart() + Math.toIntExact(slotOffset(slot) / g.blockSize()); }
    public int position(int slot) { return (int) (slotOffset(slot) % g.blockSize()); }

    public Fcb active(int slot) throws IOException {
        Fcb record = StorageCodec.readFcb(disk.readBlock(tableBlock(slot)), position(slot), g, slot);
        if (record == null) throw new IllegalArgumentException("Free FCB slot");
        return record;
    }

    @FunctionalInterface
    private interface SlotVisitor { boolean visit(int slot, Fcb record) throws IOException; }

    private int scan(SlotVisitor visitor) throws IOException {
        byte[] block = null;
        for (int slot = 0; slot < g.fcbCapacity(); slot++) {
            int offset = position(slot);
            if (offset == 0) block = disk.readBlock(tableBlock(slot));
            if (visitor.visit(slot, StorageCodec.readFcb(block, offset, g, slot))) return slot;
        }
        return -1;
    }

    public int freeSlot() throws IOException {
        int slot = scan((id, record) -> id > 0 && record == null);
        if (slot < 0) throw new IllegalArgumentException("FCB table full");
        return slot;
    }

    public record Chain(List<Integer> indices, List<Integer> data) {}

    public Chain traverse(Fcb record, AllocationBitmap bitmap, BitSet owners) throws IOException {
        int expected = Math.toIntExact(Math.ceilDiv(record.size(), g.blockSize()));
        var indices = new ArrayList<Integer>();
        var data = new ArrayList<Integer>();
        int id = record.firstIndex();
        while (id != -1) {
            claim(id, bitmap, owners);
            indices.add(id);
            IndexBlock index = StorageCodec.readIndex(disk.readBlock(id), g);
            if (index.references().size() > expected - data.size())
                throw new IllegalArgumentException("Index chain exceeds logical size");
            for (int ref : index.references()) claim(ref, bitmap, owners);
            data.addAll(index.references());
            id = index.next();
        }
        if (data.size() != expected) throw new IllegalArgumentException("Truncated index chain");
        return new Chain(indices, data);
    }

    private static void claim(int id, AllocationBitmap bitmap, BitSet owners) {
        if (!bitmap.occupied(id) || owners.get(id)) throw new IllegalArgumentException("Free or multiply owned block");
        owners.set(id);
    }

    public record DiskUsage(int reserved, int indices, int directories, int files, int links, int free) {}

    public DiskUsage inspect(AllocationBitmap bitmap) throws IOException {
        var owners = new BitSet();
        int[] counts = new int[4];
        scan((id, record) -> {
            if (record != null) {
                Chain chain = traverse(record, bitmap, owners);
                counts[0] += chain.indices().size();
                counts[1 + record.type().ordinal()] += chain.data().size();
            }
            return false;
        });
        for (int id = g.firstAllocatable(); id < g.totalBlocks(); id++)
            if (bitmap.occupied(id) != owners.get(id)) throw new IllegalArgumentException("Orphan dynamic block");
        return new DiskUsage(g.firstAllocatable(), counts[0], counts[2], counts[1], counts[3],
                g.allocatableBlocks() - owners.cardinality());
    }

    public byte[] read(Chain chain, long offset, int length) throws IOException {
        byte[] result = new byte[length];
        for (int copied = 0; copied < length;) {
            long at = offset + copied;
            int within = (int) (at % g.blockSize());
            int chunk = Math.min(length - copied, g.blockSize() - within);
            byte[] block = disk.readBlock(chain.data().get(Math.toIntExact(at / g.blockSize())));
            System.arraycopy(block, within, result, copied, chunk);
            copied += chunk;
        }
        return result;
    }
}
