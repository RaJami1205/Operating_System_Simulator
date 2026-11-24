package io.github.rajami1205.filesystem.application;

import io.github.rajami1205.filesystem.infrastructure.disk.*;
import io.github.rajami1205.filesystem.model.disk.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class IndexedStorageTest {
    @TempDir Path dir;
    private final DiskGeometry g = new DiskGeometry(1 << 20, 512);

    private Path image(String name, DiskGeometry geometry) throws IOException {
        Path path = dir.resolve(name + ".fs");
        try (var disk = VirtualDisk.create(path, geometry, "D02")) { return path; }
    }

    private Fcb file(IndexedStorage s) throws IOException { return s.create(Fcb.Type.FILE, 48, 1, 1, 0); }

    @Test void boundariesNoncontiguousGrowthAndReopen() throws Exception {
        Path path = image("boundaries", g);
        int first = g.firstAllocatable();
        try (var s = IndexedStorage.open(path)) {
            assertEquals(g.allocatableBlocks(), s.inspect().free());
            assertEquals(1, file(s).id());
            assertEquals(2, s.create(Fcb.Type.DIRECTORY, 56, 1, 1, 0).id());
            for (int n : new int[]{0, 1, 512, 513, 124 * 512, 124 * 512 + 1}) {
                byte[] payload = new byte[n];
                for (int i = 0; i < n; i++) payload[i] = (byte) (i % 251);
                s.replace(1, payload);
                if (n == 1) s.replace(2, new byte[]{7});
                assertArrayEquals(payload, s.read(1, 0, n));
                if (n > 512) assertArrayEquals(Arrays.copyOfRange(payload, 511, 513), s.read(1, 511, 2));
                if (n > 124 * 512) assertArrayEquals(Arrays.copyOfRange(payload, n - 2, n), s.read(1, n - 2, 2));
                assertThrows(IllegalArgumentException.class, () -> s.read(1, Long.MAX_VALUE, 1));
            }
            assertEquals(new IndexedStorage.ResourceUsage(63489, 125, 2, 65024, 511, 1024), s.usage(1));
            assertEquals(3, s.inspect().indices());
            assertEquals(1, s.inspect().directories());
            assertEquals(125, s.inspect().files());
        }
        try (var disk = VirtualDisk.open(path)) {
            IndexBlock head = StorageCodec.readIndex(disk.readBlock(first), g);
            assertEquals(first + 1, head.references().getFirst());
            assertEquals(first + 4, head.references().get(1));
            IndexBlock last = StorageCodec.readIndex(disk.readBlock(head.next()), g);
            assertEquals(head.next() + 1, last.references().getFirst());
            assertEquals(-1, last.next());
            byte[] tail = disk.readBlock(last.references().getFirst());
            assertArrayEquals(new byte[511], Arrays.copyOfRange(tail, 1, 512));
        }
        try (var s = IndexedStorage.open(path)) {
            assertEquals(2, s.usage(1).indexBlocks());
            s.replace(1, new byte[]{99});
            assertEquals(first, s.record(1).firstIndex());
            s.replace(1, new byte[0]);
            assertEquals(-1, s.record(1).firstIndex());
            s.delete(2);
            assertEquals(g.allocatableBlocks(), s.inspect().free());
            assertEquals(2, file(s).id());
        }
    }

    @Test void allSlotsAndNeighborRecords() throws Exception {
        Path path = image("slots", g);
        try (var s = IndexedStorage.open(path)) {
            for (int i = 1; i < g.fcbCapacity(); i++) assertEquals(i, file(s).id());
            assertThrows(IllegalArgumentException.class, () -> file(s));
            var neighbor = s.record(2);
            s.delete(1);
            assertEquals(1, file(s).id());
            assertEquals(neighbor, s.record(2));
            assertThrows(IllegalArgumentException.class, () -> s.delete(0));
        }
    }

    @Test void reusedDataTailIsClearedAndLinkAccountingIsDerived() throws Exception {
        Path path = image("reuse", g);
        int first = g.firstAllocatable();
        try (var s = IndexedStorage.open(path)) {
            file(s);
            byte[] old = new byte[512];
            Arrays.fill(old, (byte) 7);
            s.replace(1, old);
            s.delete(1);
            var link = s.create(Fcb.Type.SYMBOLIC_LINK, 63, 1, 1, 0);
            s.replace(link.id(), new byte[]{9});
            assertEquals(first, s.record(link.id()).firstIndex());
            assertEquals(1, s.inspect().links());
            assertEquals(0, s.inspect().files());
            assertThrows(IllegalArgumentException.class, () -> s.read(link.id(), 0, 2));
        }
        try (var disk = VirtualDisk.open(path)) {
            byte[] expected = new byte[512];
            expected[0] = 9;
            assertArrayEquals(expected, disk.readBlock(first + 1));
        }
    }

    @Test void fullDiskReplacement() throws Exception {
        Path path = image("full", g);
        try (var s = IndexedStorage.open(path)) {
            file(s);
            int data = g.allocatableBlocks();
            while (data + Math.ceilDiv(data, 124) > g.allocatableBlocks()) data--;
            byte[] content = new byte[data * 512];
            s.replace(1, content);
            assertEquals(0, s.inspect().free());
            content[0] = 77;
            s.replace(1, content);
            assertArrayEquals(new byte[]{77}, s.read(1, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> s.replace(1, Arrays.copyOf(content, content.length + 1)));
            assertEquals(0, s.inspect().free());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"write", "flush", "restore", "restoreFlush"})
    void realIoFailureRestoresOrFaults(String failure) throws Exception {
        Path path = image("rollback", g);
        try (var s = IndexedStorage.open(path, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), step -> {})) {
            file(s);
            s.replace(1, new byte[]{11});
        }
        byte[] before = Files.readAllBytes(path);
        int[] writes = {0};
        var primary = new IOException("commit");
        var secondary = new IOException("rollback");
        try (var s = IndexedStorage.open(path, Clock.systemUTC(), step -> {
            if (step == IndexedStorage.Step.WRITE && ++writes[0] == 3 && !failure.equals("flush")) throw primary;
            if (step == IndexedStorage.Step.FLUSH && failure.equals("flush")) throw primary;
            if (step == IndexedStorage.Step.RESTORE && failure.equals("restore")) throw secondary;
            if (step == IndexedStorage.Step.RESTORE_FLUSH && failure.equals("restoreFlush")) throw secondary;
        })) {
            assertSame(primary, assertThrows(IOException.class, () -> s.replace(1, new byte[1025])));
            if (failure.startsWith("restore")) {
                assertSame(secondary, primary.getSuppressed()[0]);
                assertThrows(IllegalStateException.class, s::inspect);
                assertThrows(IllegalStateException.class, () -> s.read(1, 0, 1));
                assertThrows(IllegalStateException.class, () -> file(s));
                assertThrows(IOException.class, () -> IndexedStorage.open(path));
            } else {
                assertEquals(0, primary.getSuppressed().length);
                assertEquals(0, s.record(1).modified());
                assertArrayEquals(new byte[]{11}, s.read(1, 0, 1));
            }
        }
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test void identicalReplaceAndTruncationUpdateOnlyModificationTime() throws Exception {
        Path path = image("time", g);
        try (var s = IndexedStorage.open(path, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), step -> {})) {
            Fcb fcb = file(s);
            assertEquals(fcb.created(), fcb.modified());
            s.replace(1, new byte[]{1});
        }
        for (int time : new int[]{10, 20}) {
            try (var s = IndexedStorage.open(path, Clock.fixed(Instant.ofEpochMilli(time), ZoneOffset.UTC), step -> {})) {
                s.replace(1, time == 10 ? new byte[]{1} : new byte[0]);
                assertEquals(0, s.record(1).created());
                assertEquals(time, s.record(1).modified());
            }
        }
    }

    @Test void exactTransactionBudgetAndOverBudgetBeforeWrites() throws Exception {
        Path path = image("budget", new DiskGeometry(32L << 20, 4096));
        int[] writes = {0};
        try (var s = IndexedStorage.open(path, Clock.systemUTC(), step -> {
            if (step == IndexedStorage.Step.WRITE) writes[0]++;
        })) {
            file(s);
            writes[0] = 0;
            // 4089 data + 5 indices + one bitmap page + one FCB page.
            byte[] original = new byte[4089 * 4096];
            Arrays.fill(original, (byte) 7);
            s.replace(1, original);
            assertEquals(4096, writes[0]);
            Fcb record = s.record(1);
            var usage = s.inspect();
            writes[0] = 0;
            assertThrows(IllegalArgumentException.class, () -> s.replace(1, new byte[4090 * 4096]));
            // Also exceeds disk capacity: allocation planning would instead report insufficient space.
            var oversized = assertThrows(IllegalArgumentException.class, () -> s.replace(1, new byte[32 << 20]));
            assertEquals("Transaction exceeds 32 MiB", oversized.getMessage());
            assertEquals(0, writes[0]);
            assertEquals(record, s.record(1));
            assertEquals(usage, s.inspect());
            assertArrayEquals(original, s.read(1, 0, original.length));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicate", "role", "cycle", "free", "orphan", "range", "size", "record"})
    void corruptedOwnershipRejectedWithoutWrites(String corruption) throws Exception {
        Path path = image("invalid", g);
        int first = g.firstAllocatable();
        try (var s = IndexedStorage.open(path)) {
            file(s);
            file(s);
            s.replace(1, new byte[124 * 512 + 1]);
            if (!corruption.equals("duplicate")) s.replace(2, new byte[]{1});
        }
        try (var disk = VirtualDisk.open(path)) {
            int block = switch (corruption) {
                case "duplicate", "size", "record" -> g.fcbStart();
                case "free", "orphan" -> 2;
                default -> first;
            };
            byte[] bytes = disk.readBlock(block);
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            switch (corruption) {
                case "duplicate" -> {
                    // Slot 2 was empty. Share slot 1's single-reference terminal index.
                    b.putInt(256 + 20, first + 1);
                    b.putLong(256 + 24, 1L);
                }
                case "role" -> b.putInt(16, first + 1);
                case "cycle" -> b.putInt(4, first);
                case "free" -> bytes[(first + 2) / 8] &= (byte) ~(1 << ((first + 2) % 8));
                case "orphan" -> bytes[200] |= 1;
                case "range" -> b.putInt(16, g.totalBlocks());
                case "size" -> b.putLong(128 + 24, Long.MAX_VALUE);
                case "record" -> bytes[127] = 1;
            }
            disk.writeBlock(block, bytes);
        }
        byte[] broken = Files.readAllBytes(path);
        assertThrows(IllegalArgumentException.class, () -> IndexedStorage.open(path));
        assertArrayEquals(broken, Files.readAllBytes(path));
    }
}
