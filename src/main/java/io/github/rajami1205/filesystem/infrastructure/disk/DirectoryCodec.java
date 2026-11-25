package io.github.rajami1205.filesystem.infrastructure.disk;

import io.github.rajami1205.filesystem.model.disk.DirectoryEntry;
import io.github.rajami1205.filesystem.model.disk.DiskGeometry;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class DirectoryCodec {
    private DirectoryCodec() {}

    public static int checkedSize(long bytes, DiskGeometry g) {
        if (bytes < 0 || bytes > 264L * (g.fcbCapacity() - 1))
            throw new IllegalArgumentException("Invalid directory payload size");
        return Math.toIntExact(bytes);
    }

    private static int size(int nameBytes) { return (8 + nameBytes + 3) & ~3; }

    public static int encodedSize(List<DirectoryEntry> entries) {
        int bytes = 0;
        for (var entry : entries)
            bytes = Math.addExact(bytes, size(DirectoryEntry.utf8Length(entry.name(), 255)));
        return bytes;
    }

    public static byte[] encode(List<DirectoryEntry> entries, DiskGeometry g) {
        if (entries.size() >= g.fcbCapacity()) throw new IllegalArgumentException("Too many directory entries");
        ByteBuffer b = ByteBuffer.allocate(checkedSize(encodedSize(entries), g)).order(ByteOrder.LITTLE_ENDIAN);
        var names = new HashSet<String>();
        for (var entry : entries) {
            if (entry.child() >= g.fcbCapacity() || !names.add(entry.name()))
                throw new IllegalArgumentException("Invalid child or duplicate name");
            byte[] name = entry.name().getBytes(StandardCharsets.UTF_8);
            int end = b.position() + size(name.length);
            b.putInt(entry.child()).putShort((short) name.length).putShort((short) 0).put(name);
            b.position(end);
        }
        return b.array();
    }

    public static List<DirectoryEntry> decode(byte[] bytes, DiskGeometry g) {
        checkedSize(bytes.length, g);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var entries = new ArrayList<DirectoryEntry>();
        var names = new HashSet<String>();
        while (b.hasRemaining()) {
            int start = b.position();
            if (b.remaining() < 8 || entries.size() >= g.fcbCapacity() - 1)
                throw new IllegalArgumentException("Invalid entry count or truncated header");
            int child = b.getInt();
            int n = Short.toUnsignedInt(b.getShort());
            if (b.getShort() != 0 || n < 1 || n > 255 || child <= 0 || child >= g.fcbCapacity()
                    || size(n) > bytes.length - start)
                throw new IllegalArgumentException("Invalid directory entry header");
            String name;
            try {
                name = StandardCharsets.UTF_8.newDecoder().decode(b.slice(b.position(), n)).toString();
            } catch (CharacterCodingException e) { throw new IllegalArgumentException("Invalid UTF-8 name", e); }
            b.position(b.position() + n);
            while (b.position() < start + size(n))
                if (b.get() != 0) throw new IllegalArgumentException("Nonzero directory padding");
            if (!names.add(name)) throw new IllegalArgumentException("Duplicate directory name");
            entries.add(new DirectoryEntry(child, name));
        }
        return entries;
    }
}
