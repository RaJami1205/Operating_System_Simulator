package io.github.rajami1205.filesystem.application;

import io.github.rajami1205.filesystem.infrastructure.disk.DirectoryCodec;
import io.github.rajami1205.filesystem.infrastructure.disk.VirtualDisk;
import io.github.rajami1205.filesystem.model.disk.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static io.github.rajami1205.filesystem.application.DirectoryHierarchy.Mode.*;
import static io.github.rajami1205.filesystem.model.disk.Fcb.Type.*;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryHierarchyTest {
    @TempDir Path dir;
    final DiskGeometry g = new DiskGeometry(1 << 20, 512);

    Path image(DiskGeometry geometry) throws IOException {
        Path path = dir.resolve("tree.fs");
        try (var disk = VirtualDisk.create(path, geometry, "tree")) { return path; }
    }

    Fcb child(IndexedStorage s, int parent, String name, Fcb.Type type) throws IOException {
        return s.linkChild(parent, name, type, 060, 1, 1, new byte[0]);
    }

    @Test void emptyImageAndRootBoundaries() throws Exception {
        Path path = image(g);
        try (var s = IndexedStorage.open(path)) {
            assertThrows(IllegalStateException.class, s::inspectHierarchy);
            assertThrows(IllegalStateException.class, () -> s.resolve(0, "/", FOLLOW_FINAL_LINK));
            s.create(FILE, 0, 0, 0, 0);
            assertThrows(IllegalStateException.class, s::initializeRoot);
            s.delete(1);
            s.initializeRoot();
            var root = s.record(0);
            assertEquals(DIRECTORY, root.type());
            assertEquals(075, root.mode());
            assertEquals(1, root.group());
            assertEquals(0, root.owner());
            assertEquals(0, root.parent());
            assertEquals(-1, root.firstIndex());
            assertThrows(IllegalStateException.class, s::initializeRoot);
            assertThrows(IllegalArgumentException.class, () -> s.delete(0));
            assertEquals(root, s.record(0));
        }
    }

    @Test void traversalModesAndReconstruction() throws Exception {
        try (var s = IndexedStorage.open(image(g))) {
            s.initializeRoot();
            int d = child(s, 0, " D ", DIRECTORY).id();
            int f = child(s, d, "é", FILE).id();
            int link = child(s, d, "link", SYMBOLIC_LINK).id();
            assertEquals("/ D /é", s.absolutePath(f));
            assertEquals(f, s.resolve(d, "./..// D /é", FOLLOW_FINAL_LINK).resource().id());
            assertEquals(d, s.resolve(999, "// D ///", FOLLOW_FINAL_LINK).resource().id());
            for (String root : List.of("/", "///", "/../../"))
                assertEquals(0, s.resolve(d, root, FOLLOW_FINAL_LINK).resource().id());
            assertEquals(link, s.resolve(d, "link", NO_FOLLOW_FINAL_LINK).resource().id());
            assertThrows(UnsupportedOperationException.class, () -> s.resolve(d, "link", FOLLOW_FINAL_LINK));
            for (String path : List.of("link/", "link/..", "link/file"))
                assertThrows(UnsupportedOperationException.class, () -> s.resolve(d, path, NO_FOLLOW_FINAL_LINK));
            for (String path : List.of("", "É", "é/", "é/.", "é/..", "/missing/../x"))
                assertThrows(IllegalArgumentException.class, () -> s.resolve(d, path, FOLLOW_FINAL_LINK));
            var parent = s.resolve(d, "../ D /new", RESOLVE_PARENT);
            assertEquals(d, parent.resource().id());
            assertEquals("new", parent.finalName());
            for (String path : List.of("//", ".", "..", "new/"))
                assertThrows(IllegalArgumentException.class, () -> s.resolve(d, path, RESOLVE_PARENT));
            assertThrows(IllegalArgumentException.class, () -> s.resolve(f, ".", FOLLOW_FINAL_LINK));
        }
    }

    @Test void effectiveAndOriginalUtf8Limits() throws Exception {
        try (var s = IndexedStorage.open(image(g))) {
            s.initializeRoot();
            String segment = "é".repeat(127) + "x";
            int parent = 0;
            for (int i = 0; i < 15; i++) parent = child(s, parent, segment, DIRECTORY).id();
            int cwd = parent;
            int end = child(s, cwd, segment, DIRECTORY).id();
            String full = ("/" + segment).repeat(16);
            assertEquals(full, s.absolutePath(end));
            assertEquals(end, s.resolve(cwd, segment, FOLLOW_FINAL_LINK).resource().id());
            assertEquals(end, s.resolve(0, full, FOLLOW_FINAL_LINK).resource().id());
            assertThrows(IllegalArgumentException.class, () -> s.resolve(0, full + "/", FOLLOW_FINAL_LINK));
            assertThrows(IllegalArgumentException.class, () -> s.resolve(0, "/".repeat(4097), FOLLOW_FINAL_LINK));
            assertThrows(IllegalArgumentException.class, () -> s.resolve(end, "x", RESOLVE_PARENT));
            var before = s.inspect();
            assertThrows(IllegalArgumentException.class, () -> child(s, end, "x", FILE));
            assertEquals(before, s.inspect());
            // D02 can store a structurally sound FCB and directory payload with an overlong D03 path.
            Fcb unreachableByPath = s.create(FILE, 0, 1, 1, end);
            s.replace(end, DirectoryCodec.encode(List.of(new DirectoryEntry(unreachableByPath.id(), "x")), g));
            assertThrows(IllegalArgumentException.class, s::inspectHierarchy);
        }
    }

    @Test void sharedTableAndBitmapOrderingCompactionAndPersistence() throws Exception {
        Path path = image(g);
        String first = "z".repeat(255), second = "a".repeat(255);
        Fcb nested;
        byte[] payload = new byte[800];
        Arrays.fill(payload, (byte) 19);
        try (var s = IndexedStorage.open(path)) {
            s.initializeRoot();
            var child = s.linkChild(0, first, FILE, 060, 1, 1, payload);
            assertEquals(1, child.id());
            assertEquals(s.record(0).modified(), child.created());
            assertEquals(child.created(), child.modified());
            child(s, 0, second, DIRECTORY);
            child(s, 0, "tail", FILE);
            assertThrows(IllegalArgumentException.class, () -> child(s, 0, first, DIRECTORY));
            assertEquals(2, s.inspect().directories());
            assertEquals(2, s.inspect().files());
            assertArrayEquals(payload, s.read(1, 0, 800));
            assertEquals(List.of(first, second, "tail"), names(s));
            nested = child(s, 2, "nested", FILE);
            assertThrows(IllegalArgumentException.class, () -> s.removeChild(0, second));
        }
        try (var s = IndexedStorage.open(path)) {
            s.inspectHierarchy();
            String nestedPath = "/" + second + "/nested";
            assertEquals(nested, s.resolve(0, nestedPath, FOLLOW_FINAL_LINK).resource());
            assertEquals(nestedPath, s.absolutePath(nested.id()));
            assertEquals(List.of(first, second, "tail"), names(s));
            s.removeChild(2, "nested");
            s.removeChild(0, second);
            assertEquals("/" + first, s.absolutePath(1));
            assertEquals(List.of(first, "tail"), names(s));
            assertArrayEquals(payload, s.read(1, 0, 800));
            s.removeChild(0, first);
            s.removeChild(0, "tail");
            assertEquals(-1, s.record(0).firstIndex());
            assertEquals(g.allocatableBlocks(), s.inspect().free());
        }
    }

    List<String> names(IndexedStorage s) throws IOException {
        return DirectoryCodec.decode(s.read(0, 0, Math.toIntExact(s.record(0).size())), g)
                .stream().map(DirectoryEntry::name).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"duplicateName", "duplicateChild", "free", "range", "parent", "unreachable",
            "self", "cycle", "rootEntry", "rootMode", "rootGroup", "oversize"})
    void d02ValidCorruptionRejectedWithoutRepair(String violation) throws Exception {
        Path path = image(g);
        try (var s = IndexedStorage.open(path)) {
            s.initializeRoot();
            var first = s.create(DIRECTORY, 070, 1, 1, violation.equals("self") ? 1 :
                    violation.equals("cycle") ? 2 : 0);
            switch (violation) {
                case "duplicateName" -> {
                    s.create(FILE, 060, 1, 1, 0);
                    byte[] bytes = DirectoryCodec.encode(List.of(new DirectoryEntry(1, "a"), new DirectoryEntry(2, "b")), g);
                    bytes[20] = 'a';
                    s.replace(0, bytes);
                }
                case "duplicateChild" -> s.replace(0, DirectoryCodec.encode(
                        List.of(new DirectoryEntry(1, "a"), new DirectoryEntry(1, "b")), g));
                case "free" -> s.replace(0, DirectoryCodec.encode(
                        List.of(new DirectoryEntry(1, "a"), new DirectoryEntry(2, "b")), g));
                case "range", "rootEntry" -> {
                    byte[] bytes = DirectoryCodec.encode(List.of(new DirectoryEntry(1, "a"), new DirectoryEntry(2, "b")), g);
                    java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            .putInt(12, violation.equals("range") ? g.fcbCapacity() : 0);
                    s.replace(0, bytes);
                }
                case "parent" -> {
                    s.create(FILE, 060, 1, 1, first.id());
                    s.replace(0, DirectoryCodec.encode(List.of(new DirectoryEntry(1, "a"), new DirectoryEntry(2, "b")), g));
                }
                case "self" -> s.replace(1, DirectoryCodec.encode(List.of(new DirectoryEntry(1, "self")), g));
                case "cycle" -> {
                    s.create(DIRECTORY, 070, 1, 1, 1);
                    s.replace(1, DirectoryCodec.encode(List.of(new DirectoryEntry(2, "b")), g));
                    s.replace(2, DirectoryCodec.encode(List.of(new DirectoryEntry(1, "a")), g));
                }
                case "oversize" -> {
                    var entries = new ArrayList<DirectoryEntry>();
                    for (int id = 1; id < g.fcbCapacity(); id++) {
                        if (id != first.id()) s.create(FILE, 060, 1, 1, 0);
                        String suffix = Integer.toString(id);
                        entries.add(new DirectoryEntry(id, "x".repeat(255 - suffix.length()) + suffix));
                    }
                    byte[] maximum = DirectoryCodec.encode(entries, g);
                    assertEquals(264L * (g.fcbCapacity() - 1), maximum.length);
                    s.replace(0, maximum);
                    s.inspectHierarchy();
                    s.replace(0, Arrays.copyOf(maximum, maximum.length + 1));
                }
                case "rootMode", "rootGroup" -> s.delete(1);
                case "unreachable" -> { /* Slot 1 is active with no incoming entry. */ }
                default -> throw new AssertionError(violation);
            }
        }
        if (violation.equals("rootMode") || violation.equals("rootGroup")) {
            try (var disk = VirtualDisk.open(path)) {
                byte[] table = disk.readBlock(g.fcbStart());
                table[violation.equals("rootMode") ? 2 : 12] = 0;
                disk.writeBlock(g.fcbStart(), table);
            }
        }
        byte[] before = Files.readAllBytes(path);
        try (var s = IndexedStorage.open(path)) {
            var rejection = assertThrows(IllegalArgumentException.class, s::inspectHierarchy);
            if (violation.equals("oversize")) assertEquals("Invalid directory payload size", rejection.getMessage());
            assertThrows(IllegalArgumentException.class, () -> s.absolutePath(0));
        }
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test void sharedFcbPageExactBudgetAndRejections() throws Exception {
        Path path = image(new DiskGeometry(32L << 20, 4096));
        int[] writes = {0};
        try (var s = IndexedStorage.open(path, Clock.systemUTC(), step -> {
            if (step == IndexedStorage.Step.WRITE) writes[0]++;
        })) {
            s.initializeRoot();
            writes[0] = 0;
            assertThrows(IllegalArgumentException.class,
                    () -> s.linkChild(0, "large", FILE, 0, 1, 1, new byte[4088 * 4096]));
            assertEquals(0, writes[0]);
            s.inspectHierarchy();
            assertEquals(0, s.record(0).size());
            // Child data + 5 child indices + 2 parent blocks + bitmap + one shared FCB page.
            s.linkChild(0, "fits", FILE, 0, 1, 1, new byte[4087 * 4096]);
            assertEquals(4096, writes[0]);
            s.inspectHierarchy();
        }
    }

    @Test void oneTimestampPerMutationAndReadOnlyTraversal() throws Exception {
        Path path = image(g);
        Clock advancing = new Clock() {
            private long millis = 10;
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { throw new UnsupportedOperationException(); }
            @Override public Instant instant() { return Instant.ofEpochMilli(millis++); }
        };
        int[] writes = {0};
        try (var s = IndexedStorage.open(path, advancing, step -> {
            if (step == IndexedStorage.Step.WRITE) writes[0]++;
        })) {
            s.initializeRoot();
            var child = child(s, 0, "clock", DIRECTORY);
            assertEquals(10, s.record(0).created());
            assertEquals(11, child.created());
            assertEquals(11, child.modified());
            assertEquals(11, s.record(0).modified());
            int committed = writes[0];
            s.inspectHierarchy();
            assertEquals("/clock", s.absolutePath(child.id()));
            assertEquals(child, s.resolve(0, "clock", FOLLOW_FINAL_LINK).resource());
            assertEquals(committed, writes[0]);
            s.removeChild(0, "clock");
            assertEquals(10, s.record(0).created());
            assertEquals(12, s.record(0).modified());
        }
    }

    @Test void capacityFailureDoesNotPublishStagedParent() throws Exception {
        Path path = image(g);
        try (var s = IndexedStorage.open(path)) { s.initializeRoot(); }
        byte[] before = Files.readAllBytes(path);
        try (var s = IndexedStorage.open(path)) {
            assertThrows(IllegalArgumentException.class, () -> s.linkChild(0, "large", FILE, 0, 1, 1, new byte[1 << 20]));
            s.inspectHierarchy();
        }
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @ParameterizedTest
    @ValueSource(strings = {"insert", "remove", "fault"})
    void rollbackAfterPhysicalWrites(String operation) throws Exception {
        Path path = image(g);
        try (var s = IndexedStorage.open(path)) {
            s.initializeRoot();
            s.linkChild(0, "old", FILE, 0, 1, 1, new byte[900]);
        }
        byte[] before = Files.readAllBytes(path);
        IOException primary = new IOException("commit flush");
        IOException secondary = new IOException("rollback flush");
        try (var s = IndexedStorage.open(path, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), step -> {
            if (step == IndexedStorage.Step.FLUSH) throw primary;
            if (step == IndexedStorage.Step.RESTORE_FLUSH && operation.equals("fault")) throw secondary;
        })) {
            assertSame(primary, assertThrows(IOException.class, () -> {
                if (operation.equals("remove")) s.removeChild(0, "old");
                else s.linkChild(0, "new", FILE, 0, 1, 1, new byte[700]);
            }));
            if (operation.equals("fault")) {
                assertSame(secondary, primary.getSuppressed()[0]);
                assertThrows(IllegalStateException.class, s::inspectHierarchy);
                assertThrows(IOException.class, () -> IndexedStorage.open(path));
            } else {
                s.inspectHierarchy();
                s.inspect();
                assertEquals("old", names(s).getFirst());
            }
        }
        assertArrayEquals(before, Files.readAllBytes(path));
    }
}
