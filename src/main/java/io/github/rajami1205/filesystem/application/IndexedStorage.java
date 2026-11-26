package io.github.rajami1205.filesystem.application;

import io.github.rajami1205.filesystem.infrastructure.disk.IndexedLayout;
import io.github.rajami1205.filesystem.infrastructure.disk.DirectoryCodec;
import io.github.rajami1205.filesystem.infrastructure.disk.IndexedLayout.Chain;
import io.github.rajami1205.filesystem.infrastructure.disk.StorageCodec;
import io.github.rajami1205.filesystem.infrastructure.disk.VirtualDisk;
import io.github.rajami1205.filesystem.model.disk.*;
import io.github.rajami1205.filesystem.infrastructure.disk.IdentityCodec;
import io.github.rajami1205.filesystem.model.identity.Credential;
import io.github.rajami1205.filesystem.model.identity.Identity.Group;
import io.github.rajami1205.filesystem.model.identity.Identity.User;
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

    enum Step { WRITE, FLUSH, RESTORE, RESTORE_FLUSH, BOOTSTRAP_PERSISTED, CREDENTIAL_SNAPSHOT }
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

    static IndexedStorage createIncomplete(Path path, DiskGeometry geometry, String volume, Completion completion)
            throws IOException {
        Objects.requireNonNull(completion);
        return new IndexedStorage(VirtualDisk.create(path, geometry, volume), Clock.systemUTC(), completion);
    }

    private IndexedStorage(VirtualDisk disk, Clock clock, Completion completion) {
        this.disk = disk;
        this.clock = Objects.requireNonNull(clock);
        this.completion = Objects.requireNonNull(completion);
        g = disk.geometry();
        layout = new IndexedLayout(disk);
    }

    void ready() {
        if (closed || faulted) throw new IllegalStateException(closed ? "Closed" : "FAULTED");
    }

    long now() { return clock.millis(); }

    void completed(Step step) throws IOException { completion.after(step); }

    Transaction begin() {
        ready();
        return new Transaction();
    }

    boolean formatted() throws IOException { return disk.formatted(); }

    VirtualDisk.Owner owner() throws IOException { return disk.owner(); }
    synchronized boolean faulted() { return faulted; }

    void publishFormat() throws IOException {
        try (var tx = begin()) {
            VirtualDisk.prepareSuperblockPublication(tx.edit(1));
            try { tx.commit(); }
            catch (IOException | RuntimeException failure) {
                if (faulted) failure.addSuppressed(new IndeterminateFormat("Persisted format status indeterminate", null));
                throw failure;
            }
        }
    }

    static final class IndeterminateFormat extends IOException {
        IndeterminateFormat(String message, Throwable cause) { super(message, cause); }
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
        var tx = new Transaction();
        prepareDelete(old, tx);
        tx.commit();
    }

    private void prepareDelete(Fcb old, Transaction tx) throws IOException {
        AllocationBitmap bitmap = tx.allocation();
        Chain chain = tx.view.traverse(old, bitmap, new BitSet());
        var released = new ArrayList<>(chain.indices());
        released.addAll(chain.data());
        tx.bitmap(bitmap.release(released), released);
        tx.record(old.id(), null);
    }

    public synchronized void replace(int slot, byte[] content) throws IOException {
        Fcb old = record(slot);
        var tx = new Transaction();
        prepareReplacement(old, content, clock.millis(), tx);
        tx.commit();
    }

    private void prepareReplacement(Fcb old, byte[] content, long time, Transaction tx) throws IOException {
        int size = g.blockSize();
        int perIndex = IndexBlock.capacity(g);
        int dataCount = Math.ceilDiv(content.length, size);
        int indexCount = Math.ceilDiv(dataCount, perIndex);
        // Replacement always stages every resulting data/index block and one FCB page.
        long requiredBlocks = Math.addExact(Math.addExact((long) dataCount, indexCount), 1L);
        tx.checkStagingBudget(requiredBlocks);
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
        tx.record(old.id(), old.replace(first, content.length, time));
    }

    private Chain prepareAllocation(Fcb old, int indexCount, int dataCount, Transaction tx) throws IOException {
        AllocationBitmap bitmap = tx.allocation();
        Chain chain = tx.view.traverse(old, bitmap, new BitSet());
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

    DirectoryHierarchy hierarchy() throws IOException {
        ready();
        return new DirectoryHierarchy(layout, g, disk.readBitmap());
    }

    synchronized void inspectHierarchy() throws IOException { hierarchy(); }

    synchronized DirectoryHierarchy.Resolution resolve(int cwd, String path, DirectoryHierarchy.Mode mode)
            throws IOException {
        return hierarchy().resolve(cwd, path, mode);
    }

    synchronized String absolutePath(int slot) throws IOException { return hierarchy().path(slot); }

    synchronized void initializeRoot() throws IOException {
        ready();
        var tx = new Transaction();
        prepareRoot(tx, clock.millis());
        tx.commit();
    }

    void prepareRoot(Transaction tx, long time) throws IOException {
        for (Fcb record : tx.view.records())
            if (record != null) throw new IllegalStateException("Root preparation requires an empty FCB table");
        tx.record(0, new Fcb(Fcb.Type.DIRECTORY, 075, 0, 0, 1, 0, -1, 0, time, time));
    }

    synchronized Fcb linkChild(int parentId, String name, Fcb.Type type, int mode, int owner, int group,
                               byte[] content) throws IOException {
        DirectoryHierarchy tree = hierarchy();
        var tx = new Transaction();
        Fcb result = prepareChild(tx, tree, parentId, name, type, mode, owner, group, content, clock.millis());
        tx.commit();
        return result;
    }

    Fcb prepareChild(Transaction tx, DirectoryHierarchy tree, int parentId, String name, Fcb.Type type,
                     int mode, int owner, int group, byte[] content, long time) throws IOException {
        Fcb parent = tree.active(parentId);
        if (type == Fcb.Type.DIRECTORY && content.length != 0)
            throw new IllegalArgumentException("New directory must be empty");
        int slot = tx.view.freeSlot();
        Fcb child = new Fcb(type, mode, slot, owner, group, parentId, -1, 0, time, time);
        child.validate(g, slot);
        tree.add(child, new DirectoryEntry(slot, name));
        var entries = tree.entries(parentId);
        int parentSize = DirectoryCodec.encodedSize(entries);
        int tablePages = layout.tableBlock(slot) == layout.tableBlock(parentId) ? 1 : 2;
        long minimum = Math.addExact(Math.addExact(contentBlocks(parentSize), contentBlocks(content.length)), tablePages);
        tx.checkStagingBudget(minimum);
        prepareDirectory(parent, entries, time, tx);
        prepareReplacement(child, content, time, tx);
        tree.records[parentId] = tx.view.active(parentId);
        tree.records[slot] = tx.view.active(slot);
        tree.validateCandidate();
        return tree.records[slot];
    }

    synchronized void removeChild(int parentId, String name) throws IOException {
        DirectoryHierarchy tree = hierarchy();
        Fcb parent = tree.active(parentId);
        DirectoryEntry entry = tree.lookup(parentId, name);
        Fcb child = tree.active(entry.child());
        tree.remove(parentId, entry);
        var tx = new Transaction();
        prepareDelete(child, tx);
        prepareDirectory(parent, tree.entries(parentId), clock.millis(), tx);
        tree.records[parentId] = tx.view.active(parentId);
        tree.validateCandidate();
        tx.commit();
    }

    private long contentBlocks(int bytes) {
        long data = Math.ceilDiv((long) bytes, g.blockSize());
        return Math.addExact(data, Math.ceilDiv(data, IndexBlock.capacity(g)));
    }

    private void prepareDirectory(Fcb parent, List<DirectoryEntry> entries, long time, Transaction tx)
            throws IOException {
        tx.retainedBytes = DirectoryCodec.encodedSize(entries);
        tx.checkStagingBudget(Math.max(tx.changes.size(), contentBlocks((int) tx.retainedBytes) + 1));
        prepareReplacement(parent, DirectoryCodec.encode(entries, g), time, tx);
        tx.retainedBytes = 0;
    }

    final class Transaction implements AutoCloseable {
        private final Map<Integer, Change> changes = new LinkedHashMap<>();
        private final IndexedLayout view = new IndexedLayout(g, this::read);
        private long retainedBytes;
        private long credentialBytes;

        void retainCredentials(int count) {
            credentialBytes = Math.multiplyExact((long) count, Credential.SALT_BYTES + Credential.HASH_BYTES);
        }

        private void checkStagingBudget(long blocks) {
            long extra = Math.addExact(retainedBytes, credentialBytes);
            long cost = Math.addExact(Math.multiplyExact(2L * g.blockSize(), blocks), extra);
            if (cost > 32L * 1024 * 1024) throw new IllegalArgumentException("Transaction exceeds 32 MiB");
        }

        DirectoryHierarchy hierarchy() throws IOException {
            return new DirectoryHierarchy(view, g, allocation());
        }

        void validatePhysical() throws IOException { view.inspect(allocation()); }

        IdentityCatalog identities() throws IOException {
            var catalog = new IdentityCatalog();
            for (var table : IdentityCodec.Table.values()) {
                int slot = 0;
                while (slot < table.capacity) {
                    int block = table.block(g, slot);
                    byte[] page = read(block);
                    try {
                        int end = Math.min(table.capacity, slot + g.blockSize() / table.width);
                        while (slot < end) {
                            if (table == IdentityCodec.Table.USERS) catalog.users[slot] = IdentityCodec.user(g, page, slot);
                            else catalog.groups[slot] = IdentityCodec.group(g, page, slot);
                            slot++;
                        }
                    } finally {
                        if (table == IdentityCodec.Table.USERS && !changes.containsKey(block)) Arrays.fill(page, (byte) 0);
                    }
                }
            }
            return catalog;
        }

        Credential credential(int uid) throws IOException {
            int block = IdentityCodec.Table.USERS.block(g, uid);
            byte[] page = read(block);
            try { return IdentityCodec.credential(g, page, uid); }
            finally { if (!changes.containsKey(block)) Arrays.fill(page, (byte) 0); }
        }

        void user(User value, Credential credential) throws IOException {
            IdentityCodec.writeUser(g, edit(IdentityCodec.Table.USERS.block(g, value.uid())), value, credential);
        }

        void group(Group value) throws IOException {
            IdentityCodec.writeGroup(g, edit(IdentityCodec.Table.GROUPS.block(g, value.gid())), value);
        }

        @Override public void close() {
            for (var change : changes.values()) {
                if (change.id() >= g.userStart() && change.id() < g.groupStart()) {
                    Arrays.fill(change.before(), (byte) 0);
                    Arrays.fill(change.after(), (byte) 0);
                }
            }
            changes.clear();
        }

        private byte[] read(int id) throws IOException {
            Change staged = changes.get(id);
            return staged == null ? disk.readBlock(id) : staged.after();
        }

        AllocationBitmap allocation() throws IOException {
            // Staged bitmap pages are the authoritative candidate across preparations.
            byte[] bytes = new byte[Math.toIntExact(g.bitmapRegionBytes())];
            for (int i = 0; i < g.bitmapBlocks(); i++)
                System.arraycopy(read(DiskGeometry.BITMAP_START + i), 0, bytes, i * g.blockSize(), g.blockSize());
            return AllocationBitmap.decode(g, bytes);
        }

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
