package io.github.rajami1205.filesystem.application;

import io.github.rajami1205.filesystem.infrastructure.disk.DirectoryCodec;
import io.github.rajami1205.filesystem.infrastructure.disk.IndexedLayout;
import io.github.rajami1205.filesystem.model.disk.*;
import java.io.IOException;
import java.util.*;

/** Operation-local structural view, built under IndexedStorage exclusion. */
final class DirectoryHierarchy {
    enum Mode { FOLLOW_FINAL_LINK, NO_FOLLOW_FINAL_LINK, RESOLVE_PARENT }
    record Resolution(Fcb resource, String finalName) {}
    private record Visit(int id, int pathBytes) {}

    final Fcb[] records;
    private final Map<Integer, List<DirectoryEntry>> directories = new HashMap<>();

    DirectoryHierarchy(IndexedLayout layout, DiskGeometry g, AllocationBitmap bitmap) throws IOException {
        records = layout.records();
        if (records[0] == null) throw new IllegalStateException("Directory hierarchy has no initialized root");
        if (records[0].group() != 1 || records[0].mode() != 075)
            throw new IllegalArgumentException("Invalid root attributes");
        // Decode only reachable directories. Even malformed trees cannot retain more than
        // one valid incoming entry per slot before a duplicate or invalid reference is found.
        var directoryBlocks = new BitSet();
        walk(dir -> {
            int length = DirectoryCodec.checkedSize(dir.size(), g);
            var chain = layout.traverse(dir, bitmap, directoryBlocks);
            var entries = DirectoryCodec.decode(layout.read(chain, 0, length), g);
            directories.put(dir.id(), entries);
            return entries;
        });
    }

    @FunctionalInterface
    private interface Entries { List<DirectoryEntry> of(Fcb directory) throws IOException; }

    void validateCandidate() throws IOException { walk(dir -> entries(dir.id())); }

    private void walk(Entries source) throws IOException {
        var pending = new ArrayDeque<Visit>();
        var visited = new BitSet(records.length);
        visited.set(0);
        pending.push(new Visit(0, 1));
        while (!pending.isEmpty()) {
            Visit visit = pending.pop();
            Fcb parent = records[visit.id()];
            if (parent.type() != Fcb.Type.DIRECTORY) continue;
            for (DirectoryEntry entry : source.of(parent)) {
                Fcb child = active(entry.child());
                if (child.parent() != parent.id() || visited.get(child.id()))
                    throw new IllegalArgumentException("Invalid parent or duplicate incoming directory entry");
                visited.set(child.id());
                int bytes = childLength(visit.pathBytes(), entry.name());
                pending.push(new Visit(child.id(), bytes));
            }
        }
        for (Fcb fcb : records)
            if (fcb != null && !visited.get(fcb.id())) throw new IllegalArgumentException("Unreachable FCB");
    }

    private static int childLength(int parentBytes, String name) {
        int prefix = parentBytes == 1 ? 1 : Math.addExact(parentBytes, 1);
        int bytes = Math.addExact(prefix, DirectoryEntry.utf8Length(name, 255));
        if (bytes > 4096) throw new IllegalArgumentException("Absolute path exceeds 4096 bytes");
        return bytes;
    }

    Fcb active(int id) {
        if (id < 0 || id >= records.length || records[id] == null)
            throw new IllegalArgumentException("Invalid or free FCB");
        return records[id];
    }

    List<DirectoryEntry> entries(int id) {
        requireDirectory(active(id));
        return directories.get(id);
    }

    private static void requireDirectory(Fcb fcb) {
        if (fcb.type() == Fcb.Type.SYMBOLIC_LINK)
            throw new UnsupportedOperationException("Symbolic-link traversal is not implemented");
        if (fcb.type() != Fcb.Type.DIRECTORY) throw new IllegalArgumentException("Expected directory");
    }

    void add(Fcb child, DirectoryEntry entry) {
        List<DirectoryEntry> siblings = entries(child.parent());
        childLength(DirectoryEntry.utf8Length(path(child.parent()), 4096), entry.name());
        if (siblings.stream().anyMatch(e -> e.name().equals(entry.name())))
            throw new IllegalArgumentException("Duplicate directory name");
        siblings.add(entry);
        records[child.id()] = child;
        if (child.type() == Fcb.Type.DIRECTORY) directories.put(child.id(), new ArrayList<>());
    }

    DirectoryEntry lookup(int parent, String name) {
        DirectoryEntry.validateName(name);
        for (var entry : entries(parent)) if (entry.name().equals(name)) return entry;
        throw new IllegalArgumentException("Missing directory entry");
    }

    void remove(int parent, DirectoryEntry entry) {
        Fcb child = active(entry.child());
        if (child.type() == Fcb.Type.DIRECTORY && !entries(child.id()).isEmpty())
            throw new IllegalArgumentException("Nonempty directory");
        entries(parent).remove(entry);
        records[child.id()] = null;
        directories.remove(child.id());
    }

    String path(int id) {
        var names = new ArrayDeque<String>();
        var seen = new BitSet(records.length);
        Fcb child = active(id);
        int bytes = 1;
        while (child.id() != 0) {
            if (seen.get(child.id())) throw new IllegalArgumentException("Parent cycle");
            seen.set(child.id());
            String name = null;
            for (var entry : entries(child.parent())) {
                if (entry.child() == child.id()) {
                    if (name != null) throw new IllegalArgumentException("Duplicate parent entry");
                    name = entry.name();
                }
            }
            if (name == null) throw new IllegalArgumentException("Missing parent entry");
            bytes = childLength(bytes, name);
            names.addFirst(name);
            child = active(child.parent());
        }
        return "/" + String.join("/", names);
    }

    Resolution resolve(int cwd, String path, Mode mode) {
        Objects.requireNonNull(mode);
        if (DirectoryEntry.utf8Length(path, 4096) == 0) throw new IllegalArgumentException("Empty path");
        boolean trailing = path.endsWith("/");
        if (mode == Mode.RESOLVE_PARENT && trailing) throw new IllegalArgumentException("Expected final name");
        Fcb current = active(path.startsWith("/") ? 0 : cwd);
        requireDirectory(current);
        var lengths = new ArrayDeque<Integer>();
        lengths.push(1);
        for (String name : path(current.id()).split("/"))
            if (!name.isEmpty()) lengths.push(childLength(lengths.peek(), name));
        String[] parts = path.split("/+");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            requireDirectory(current);
            if (mode == Mode.RESOLVE_PARENT && i == parts.length - 1) {
                DirectoryEntry.validateName(part);
                childLength(lengths.peek(), part);
                return new Resolution(current, part);
            }
            if (part.equals(".")) continue;
            if (part.equals("..")) {
                if (current.id() != 0) lengths.pop();
                current = active(current.parent());
            } else {
                var entry = lookup(current.id(), part);
                lengths.push(childLength(lengths.peek(), part));
                current = active(entry.child());
            }
        }
        if (trailing || mode == Mode.FOLLOW_FINAL_LINK && current.type() == Fcb.Type.SYMBOLIC_LINK)
            requireDirectory(current);
        return new Resolution(current, null);
    }
}
