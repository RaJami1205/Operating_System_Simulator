package io.github.rajami1205.osimulator.model.storage;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.storage.exception.StorageException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FileIndexTest {
    @Test
    void usesFirstFreeSlotWithoutMovingOtherEntriesAndReturnsImmutableViews() {
        var index = new FileIndex(3);
        var a = new FileIndexEntry("a", 20, 2);
        var b = new FileIndexEntry("b", 22, 1);
        var c = new FileIndexEntry("c", 23, 2);
        assertEquals(0, index.publish(a));
        assertEquals(1, index.publish(b));
        assertEquals(2, index.publish(c));
        var historical = index.entries();
        assertThrows(UnsupportedOperationException.class, historical::clear);
        assertThrows(StorageException.class, () -> index.publish(new FileIndexEntry("d", 25, 1)));
        assertTrue(index.remove("b"));
        assertFalse(index.remove("missing"));
        assertSame(EmptyStorageContent.INSTANCE, index.read(1));
        assertSame(c, index.read(2));
        var d = new FileIndexEntry("d", 25, 1);
        assertEquals(1, index.publish(d));
        assertEquals(List.of(a, d, c), index.entries());
        assertEquals(List.of(a, b, c), historical);
        assertSame(d, index.find("d").orElseThrow());
        index.reset();
        index.reset();
        assertTrue(index.entries().isEmpty());
        assertTrue(index.find("a").isEmpty());
        for (int i = 0; i < 3; i++) assertSame(EmptyStorageContent.INSTANCE, index.read(i));
        assertEquals(0, index.publish(a));
    }

    @Test
    void namesAreExactCaseSensitiveAndDuplicatesDoNotOverwrite() {
        var index = new FileIndex(3);
        var a = new FileIndexEntry("A", 10, 1);
        index.publish(a);
        index.publish(new FileIndexEntry("a", 11, 1));
        index.publish(new FileIndexEntry(" A ", 12, 1));
        assertThrows(StorageException.class, () -> index.publish(new FileIndexEntry("A", 90, 5)));
        assertSame(a, index.find("A").orElseThrow());
        assertEquals(3, index.entries().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n", "\u2003"})
    void rejectsBlankNamesEverywhere(String name) {
        var index = new FileIndex(1);
        assertThrows(IllegalArgumentException.class, () -> new FileIndexEntry(name, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> index.find(name));
        assertThrows(IllegalArgumentException.class, () -> index.remove(name));
    }

    @Test
    void validatesEntriesContentsCellsAndSlots() {
        assertThrows(NullPointerException.class, () -> new FileIndexEntry(null, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new FileIndexEntry("a", -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FileIndexEntry("a", 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new FileIndexEntry("a", 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new FileIndexEntry("a", Integer.MAX_VALUE, 1));
        assertDoesNotThrow(() -> new FileIndexEntry("a", Integer.MAX_VALUE - 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new FileIndex(0));
        assertThrows(IllegalArgumentException.class, () -> new FileIndex(-1));
        var index = new FileIndex(2);
        assertEquals(2, index.capacity());
        assertThrows(NullPointerException.class, () -> index.publish(null));
        assertThrows(NullPointerException.class, () -> index.find(null));
        assertThrows(NullPointerException.class, () -> index.remove(null));
        assertThrows(IndexOutOfBoundsException.class, () -> index.read(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> index.read(2));
        assertThrows(NullPointerException.class, () -> new StoredInstructionContent(null));
        var instruction = new LoadInstruction(RegisterName.AX);
        assertSame(instruction, new StoredInstructionContent(instruction).instruction());
        assertThrows(IllegalArgumentException.class, () -> new StorageCell(-1, StorageRegion.SWAP, EmptyStorageContent.INSTANCE));
        assertThrows(NullPointerException.class, () -> new StorageCell(0, null, EmptyStorageContent.INSTANCE));
        assertThrows(NullPointerException.class, () -> new StorageCell(0, StorageRegion.FILE_INDEX, null));
    }
}
