package io.github.rajami1205.filesystem.infrastructure.disk;

import io.github.rajami1205.filesystem.model.disk.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryCodecTest {
    final DiskGeometry geometry = new DiskGeometry(1 << 20, 512);

    @Test void independentLittleEndianSequence() {
        var entries = List.of(new DirectoryEntry(2, "é"), new DirectoryEntry(1, " a "));
        byte[] golden = HexFormat.of().parseHex("0200000002000000c3a90000010000000300000020612000");
        assertArrayEquals(golden, DirectoryCodec.encode(entries, geometry));
        assertEquals(entries, DirectoryCodec.decode(golden, geometry));
        assertEquals(List.of(), DirectoryCodec.decode(new byte[0], geometry));
        for (int i : new int[]{0, 4, 6, 8, 10}) {
            byte[] corrupt = golden.clone();
            corrupt[i] = (byte) (i == 0 || i == 4 ? 0 : 255);
            assertThrows(IllegalArgumentException.class, () -> DirectoryCodec.decode(corrupt, geometry));
        }
        for (int length : new int[]{1, 7, 8, 11, 23})
            assertThrows(IllegalArgumentException.class, () -> DirectoryCodec.decode(Arrays.copyOf(golden, length), geometry));
    }

    @Test void strictNamesAndStructuralBounds() {
        String max = "é".repeat(127) + " ";
        var entry = new DirectoryEntry(1, max);
        assertEquals(264, DirectoryCodec.encode(List.of(entry), geometry).length);
        for (String bad : List.of("", ".", "..", "\u009b", "a/b", "a\\b", "*", "?", max + "a", "\ud800"))
            assertThrows(IllegalArgumentException.class, () -> new DirectoryEntry(1, bad));
        assertThrows(IllegalArgumentException.class, () -> DirectoryCodec.checkedSize(Long.MAX_VALUE, geometry));
        byte[] many = new byte[geometry.fcbCapacity() * 12];
        for (int i = 0; i < geometry.fcbCapacity(); i++) {
            byte[] bytes = DirectoryCodec.encode(List.of(new DirectoryEntry(1, Integer.toString(i))), geometry);
            System.arraycopy(bytes, 0, many, i * 12, 12);
        }
        assertThrows(IllegalArgumentException.class, () -> DirectoryCodec.decode(many, geometry));
        assertThrows(IllegalArgumentException.class, () -> DirectoryCodec.encode(List.of(entry, entry), geometry));
    }
}
