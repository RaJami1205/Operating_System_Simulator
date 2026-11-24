package io.github.rajami1205.filesystem.application;

import io.github.rajami1205.filesystem.infrastructure.disk.IndexedLayout;
import io.github.rajami1205.filesystem.infrastructure.disk.IndexedLayout.Chain;
import io.github.rajami1205.filesystem.infrastructure.disk.StorageCodec;
import io.github.rajami1205.filesystem.infrastructure.disk.VirtualDisk;
import io.github.rajami1205.filesystem.model.disk.*;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

/** Exclusive D02 operation authority. Its physical device never escapes. */
public final class IndexedStorage implements AutoCloseable {
    private final VirtualDisk disk;
    private final DiskGeometry g;
    private final IndexedLayout layout;
    private final Clock clock;
    private final Completion completion;
    private boolean faulted;
    private boolean closed;

    enum Step { WRITE, FLUSH, RESTORE, RESTORE_FLUSH }
    @FunctionalInterface
    interface Completion { void after(Step step) throws IOException; }

    public static IndexedStorage open(Path path) throws IOException {
        return open(path, Clock.systemUTC(), step -> {});
    }

    static IndexedStorage open(Path path, Clock clock, Completion completion) throws IOException {
        VirtualDisk disk = VirtualDisk.open(path);
        try {
            IndexedStorage storage = new IndexedStorage(disk, clock, completion);
            storage.inspect();
            return storage;
        } catch (IOException | RuntimeException e) {
            try { disk.close(); } catch (IOException close) { e.addSuppressed(close); }
            throw e;
        }
    }

    private IndexedStorage(VirtualDisk disk, Clock clock, Completion completion) {
        this.disk = disk;
        this.clock = Objects.requireNonNull(clock);
        this.completion = Objects.requireNonNull(completion);
        g = disk.geometry();
        layout = new IndexedLayout(disk);
    }

    private void ready() {
        if (closed || faulted) throw new IllegalStateException(closed ? "Closed" : "FAULTED");
    }

    public synchronized IndexedLayout.DiskUsage inspect() throws IOException {
        ready();
        return layout.inspect(disk.readBitmap());
    }

    public synchronized Fcb record(int slot) throws IOException {
        ready();
        return layout.active(slot);
    }

    public synchronized Fcb create(Fcb.Type type, int mode, int owner, int group, int parent) throws IOException {
        ready();
        int id = layout.freeSlot();
        long time = clock.millis();
        Fcb record = new Fcb(type, mode, id, owner, group, parent, -1, 0, time, time);
        record.validate(g, id);
        var tx = new Transaction();
        tx.record(id, record);
        tx.commit();
        return record;
    }

    public synchronized byte[] read(int slot, long offset, int length) throws IOException {
        Fcb fcb = record(slot);
        if (offset < 0 || length < 0 || offset > fcb.size() || length > fcb.size() - offset)
            throw new IllegalArgumentException("Range outside logical content");
        return layout.read(layout.traverse(fcb, disk.readBitmap(), new BitSet()), offset, length);
    }

    public record ResourceUsage(long logicalBytes, int dataBlocks, int indexBlocks, long allocatedBytes,
                                long internalFragmentation, long indexOverhead) {}

    public synchronized ResourceUsage usage(int slot) throws IOException {
        Fcb fcb = record(slot);
        Chain chain = layout.traverse(fcb, disk.readBitmap(), new BitSet());
        long data = (long) chain.data().size() * g.blockSize();
        long overhead = (long) chain.indices().size() * g.blockSize();
        return new ResourceUsage(fcb.size(), chain.data().size(), chain.indices().size(), data + overhead,
                data - fcb.size(), overhead);
    }

    public synchronized void delete(int slot) throws IOException {
        ready();
        if (slot == 0) throw new IllegalArgumentException("Permanent root slot");
        Fcb old = record(slot);
        AllocationBitmap bitmap = disk.readBitmap();
        Chain chain = layout.traverse(old, bitmap, new BitSet());
        var released = new ArrayList<>(chain.indices());
        released.addAll(chain.data());
        var tx = new Transaction();
        tx.bitmap(bitmap.release(released), released);
        tx.record(slot, null);
        tx.commit();
    }

    public synchronized void replace(int slot, byte[] content) throws IOException {
        Fcb old = record(slot);
        int size = g.blockSize();
        int perIndex = IndexBlock.capacity(g);
        int dataCount = Math.ceilDiv(content.length, size);
        int indexCount = Math.ceilDiv(dataCount, perIndex);
        // Replacement always stages every resulting data/index block and one FCB page.
        long requiredBlocks = Math.addExact(Math.addExact((long) dataCount, indexCount), 1L);
        checkStagingBudget(requiredBlocks);
        var tx = new Transaction();
        Chain chain = prepareAllocation(old, indexCount, dataCount, tx);
        for (int i = 0; i < indexCount; i++) {
            int from = i * perIndex;
            int next = i + 1 == indexCount ? -1 : chain.indices().get(i + 1);
            IndexBlock index = new IndexBlock(next, chain.data().subList(from, Math.min(dataCount, from + perIndex)));
            StorageCodec.writeIndex(tx.edit(chain.indices().get(i)), index, g);
        }
        for (int i = 0; i < dataCount; i++) {
            byte[] target = tx.edit(chain.data().get(i));
            int from = i * size;
            int length = Math.min(size, content.length - from);
            System.arraycopy(content, from, target, 0, length);
            Arrays.fill(target, length, size, (byte) 0);
        }
        int first = indexCount == 0 ? -1 : chain.indices().getFirst();
        tx.record(slot, old.replace(first, content.length, clock.millis()));
        tx.commit();
    }

    private Chain prepareAllocation(Fcb old, int indexCount, int dataCount, Transaction tx) throws IOException {
        AllocationBitmap bitmap = disk.readBitmap();
        Chain chain = layout.traverse(old, bitmap, new BitSet());
        int extraIndices = Math.max(0, indexCount - chain.indices().size());
        int extraData = Math.max(0, dataCount - chain.data().size());
        var reservation = bitmap.allocate(extraIndices + extraData);
        List<Integer> added = reservation.blocks();
        var released = new ArrayList<Integer>();
        resize(chain.indices(), indexCount, added.subList(0, extraIndices), released);
        resize(chain.data(), dataCount, added.subList(extraIndices, added.size()), released);
        AllocationBitmap candidate = reservation.bitmap().release(released);
        tx.bitmap(candidate, added);
        tx.bitmap(candidate, released);
        return chain;
    }

    private static void resize(List<Integer> blocks, int count, List<Integer> added, List<Integer> released) {
        if (count < blocks.size()) {
            List<Integer> tail = blocks.subList(count, blocks.size());
            released.addAll(tail);
            tail.clear();
        }
        blocks.addAll(added);
    }

    private record Change(int id, byte[] before, byte[] after) {}

    private void checkStagingBudget(long blocks) {
        long cost = Math.multiplyExact(2L * g.blockSize(), blocks);
        if (cost > 32L * 1024 * 1024) throw new IllegalArgumentException("Transaction exceeds 32 MiB");
    }

    private final class Transaction {
        private final Map<Integer, Change> changes = new LinkedHashMap<>();

        byte[] edit(int id) throws IOException {
            Change existing = changes.get(id);
            if (existing != null) return existing.after();
            checkStagingBudget(changes.size() + 1L);
            byte[] before = disk.readBlock(id);
            Change change = new Change(id, before, before.clone());
            changes.put(id, change);
            return change.after();
        }

        void record(int slot, Fcb value) throws IOException {
            StorageCodec.writeFcb(edit(layout.tableBlock(slot)), layout.position(slot), value);
        }

        void bitmap(AllocationBitmap candidate, List<Integer> ids) throws IOException {
            var pages = new BitSet();
            for (int id : ids) {
                int page = id / 8 / g.blockSize();
                if (!pages.get(page)) {
                    candidate.copyPage(page, edit(DiskGeometry.BITMAP_START + page));
                    pages.set(page);
                }
            }
        }

        void commit() throws IOException {
            var attempted = new ArrayList<Change>(changes.size());
            try {
                for (Change change : changes.values()) {
                    attempted.add(change);
                    disk.writeBlock(change.id(), change.after());
                    completion.after(Step.WRITE);
                }
                disk.flush();
                completion.after(Step.FLUSH);
            } catch (IOException | RuntimeException primary) {
                for (Change change : attempted.reversed()) {
                    try {
                        disk.writeBlock(change.id(), change.before());
                        completion.after(Step.RESTORE);
                    } catch (IOException | RuntimeException secondary) { rollbackFailure(primary, secondary); }
                }
                try {
                    disk.flush();
                    completion.after(Step.RESTORE_FLUSH);
                } catch (IOException | RuntimeException secondary) { rollbackFailure(primary, secondary); }
                throw primary;
            }
        }

        private void rollbackFailure(Throwable primary, Throwable secondary) {
            faulted = true;
            if (primary != secondary) primary.addSuppressed(secondary);
        }
    }

    @Override public synchronized void close() throws IOException {
        disk.close();
        closed = true;
    }
}
