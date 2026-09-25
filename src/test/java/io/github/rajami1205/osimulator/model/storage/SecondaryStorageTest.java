package io.github.rajami1205.osimulator.model.storage;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.storage.exception.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SecondaryStorageTest {
    private final Instruction load = new LoadInstruction(RegisterName.AX);
    private final Instruction store = new StoreInstruction(RegisterName.BX);

    @ParameterizedTest
    @CsvSource({"512,64,224,448", "128,64,32,64", "129,64,32,65", "1024,128,448,896", "128,127,1,1", "8192,8191,1,1"})
    void layoutIsExactAndEveryAddressHasOneRegion(int total, int virtual, int indexSize, int swapStart) {
        var storage = new SecondaryStorage(total, virtual);
        assertEquals(total, storage.size());
        assertEquals(virtual, storage.virtualMemoryPositions());
        assertEquals(indexSize, storage.indexPositions());
        assertEquals(indexSize, storage.dataStart());
        assertEquals(swapStart, storage.dataEndExclusive());
        assertEquals(swapStart, storage.swapStart());
        for (int address = 0; address < total; address++) {
            var region = address < indexSize ? StorageRegion.FILE_INDEX
                    : address < swapStart ? StorageRegion.PROGRAM_DATA : StorageRegion.SWAP;
            assertEquals(region, storage.regionOf(address));
            assertSame(EmptyStorageContent.INSTANCE, storage.read(address));
            assertEquals(new StorageCell(address, region, EmptyStorageContent.INSTANCE), storage.cell(address));
        }
        assertThrows(IndexOutOfBoundsException.class, () -> storage.read(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> storage.cell(total));
        assertThrows(IndexOutOfBoundsException.class, () -> storage.regionOf(Integer.MAX_VALUE));
    }

    @ParameterizedTest
    @CsvSource({"127,64", "128,63", "128,128", "128,129", "-1,64", "128,-1"})
    void rejectsInvalidCapacities(int total, int virtual) {
        assertThrows(IllegalArgumentException.class, () -> new SecondaryStorage(total, virtual));
    }

    @Test
    void storesAndReadsExactAdjacentBlocksWithImmutableHistoricalViews() {
        var storage = new SecondaryStorage(128, 64);
        var input = new ArrayList<Instruction>(List.of(load, store));
        var first = storage.storeProgram("A", input);
        input.clear();
        var second = storage.storeProgram("a", List.of(store));
        assertEquals(new FileIndexEntry("A", 32, 2), first);
        assertEquals(new FileIndexEntry("a", 34, 1), second);
        assertSame(first, storage.read(0));
        assertSame(second, storage.read(1));
        assertEquals(new StoredInstructionContent(load), storage.read(32));
        assertEquals(List.of(load, store), storage.readProgram("A"));
        assertEquals(List.of(store), storage.readProgram("a"));
        var contents = storage.readProgram("A");
        var entries = storage.entries();
        assertThrows(UnsupportedOperationException.class, contents::clear);
        assertThrows(UnsupportedOperationException.class, entries::clear);
        assertTrue(storage.removeProgram("A"));
        assertEquals(List.of(load, store), contents);
        assertEquals(List.of(first, second), entries);
        assertTrue(storage.findProgram("A").isEmpty());
        assertFalse(storage.removeProgram("missing"));
        assertThrows(StorageException.class, () -> storage.readProgram("missing"));
        var reused = storage.storeProgram("new", List.of(store, load));
        assertEquals(32, reused.startAddress());
        assertSame(reused, storage.read(0));
        assertSame(second, storage.read(1));
        assertEquals(List.of(reused, second), storage.entries());
        assertEquals(List.of(store), storage.readProgram("a"));
    }

    @Test
    void validationFailuresPreserveAllStateAndAvailableCapacity() {
        var storage = new SecondaryStorage(128, 64);
        storage.storeProgram("one", List.of(load));
        var before = cells(storage);
        assertThrows(StorageException.class, () -> storage.storeProgram("one", List.of(store)));
        assertThrows(StorageException.class, () -> storage.storeProgram("empty", List.of()));
        assertThrows(NullPointerException.class, () -> storage.storeProgram("null", null));
        assertThrows(NullPointerException.class, () -> storage.storeProgram("nullElement", Arrays.asList(store, null)));
        assertThrows(StorageAllocationException.class, () -> storage.storeProgram("large", Collections.nCopies(32, store)));
        assertEquals(before, cells(storage));
        assertEquals(List.of(load), storage.readProgram("one"));
        assertEquals(33, storage.storeProgram("rest", Collections.nCopies(31, store)).startAddress());
        assertThrows(StorageAllocationException.class, () -> storage.storeProgram("extra", List.of(load)));
        for (int address = 64; address < 128; address++) assertSame(EmptyStorageContent.INSTANCE, storage.read(address));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void rejectsBlankNamesAcrossAllOperations(String name) {
        var storage = new SecondaryStorage(128, 64);
        assertThrows(IllegalArgumentException.class, () -> storage.storeProgram(name, List.of(load)));
        assertThrows(IllegalArgumentException.class, () -> storage.readProgram(name));
        assertThrows(IllegalArgumentException.class, () -> storage.findProgram(name));
        assertThrows(IllegalArgumentException.class, () -> storage.removeProgram(name));
        assertTrue(storage.entries().isEmpty());
    }

    @Test
    void rejectsNullNamesAndPreservesExactLogicalNames() {
        var storage = new SecondaryStorage(128, 64);
        assertThrows(NullPointerException.class, () -> storage.storeProgram(null, List.of(load)));
        assertThrows(NullPointerException.class, () -> storage.readProgram(null));
        assertThrows(NullPointerException.class, () -> storage.findProgram(null));
        assertThrows(NullPointerException.class, () -> storage.removeProgram(null));
        storage.storeProgram(" A ", List.of(load));
        storage.storeProgram("A", List.of(store));
        storage.storeProgram("folder/name:*", List.of(load));
        assertEquals(List.of(load), storage.readProgram(" A "));
        assertEquals(List.of(store), storage.readProgram("A"));
        assertTrue(storage.findProgram("a").isEmpty());
    }

    @Test
    void fullIndexFailsWithoutReservationLeakAndReusesItsFirstSlot() {
        // Odd nonSwap leaves one more data cell than index slots.
        var storage = new SecondaryStorage(129, 64);
        for (int i = 0; i < 32; i++) storage.storeProgram("p" + i, List.of(load));
        var before = cells(storage);
        assertThrows(StorageException.class, () -> storage.storeProgram("overflow", List.of(store)));
        assertEquals(before, cells(storage));
        storage.removeProgram("p31");
        var reused = storage.storeProgram("two", List.of(store, load));
        assertEquals(63, reused.startAddress());
        assertSame(reused, storage.read(31));
        assertEquals(List.of(store, load), storage.readProgram("two"));
        assertSame(EmptyStorageContent.INSTANCE, storage.read(65));
    }

    @Test
    void fragmentedFreeSpaceFailsAtomicallyAndRemovalCoalesces() {
        var storage = new SecondaryStorage(128, 64);
        storage.storeProgram("a", Collections.nCopies(10, load));
        storage.storeProgram("b", Collections.nCopies(10, store));
        storage.storeProgram("c", Collections.nCopies(12, load));
        storage.removeProgram("a");
        storage.removeProgram("c");
        var before = cells(storage);
        assertThrows(StorageAllocationException.class, () -> storage.storeProgram("large", Collections.nCopies(13, store)));
        assertEquals(before, cells(storage));
        assertEquals(List.copyOf(Collections.nCopies(10, store)), storage.readProgram("b"));
        storage.removeProgram("b");
        var all = storage.storeProgram("all", Collections.nCopies(32, load));
        assertEquals(32, all.startAddress());
        assertEquals(32, storage.readProgram("all").size());
    }

    @Test
    void zeroCapacityAreaSupportsResetAndControlledStoreFailure() {
        var storage = new SecondaryStorage(128, 127);
        for (int i = 0; i < 2; i++) {
            assertThrows(StorageAllocationException.class, () -> storage.storeProgram("p", List.of(load)));
            assertTrue(storage.entries().isEmpty());
            assertFalse(storage.removeProgram("p"));
            assertSame(EmptyStorageContent.INSTANCE, storage.read(0));
            assertEquals(StorageRegion.SWAP, storage.regionOf(1));
            storage.reset();
        }
    }

    @Test
    void resetClearsIndexContentAndReservationsWhilePreservingLayout() {
        var storage = new SecondaryStorage(512, 64);
        storage.storeProgram("p", Collections.nCopies(224, load));
        storage.reset();
        storage.reset();
        assertTrue(storage.entries().isEmpty());
        assertTrue(storage.findProgram("p").isEmpty());
        for (int address = 0; address < storage.size(); address++) assertSame(EmptyStorageContent.INSTANCE, storage.read(address));
        assertEquals(224, storage.dataStart());
        assertEquals(448, storage.swapStart());
        var entry = storage.storeProgram("p", Collections.nCopies(224, store));
        assertEquals(224, entry.startAddress());
        assertSame(entry, storage.read(0));
    }

    @Test
    void publicationFailureAfterWritingRollsBackContentEntryAndRealAllocation() throws Exception {
        var storage = new SecondaryStorage(128, 64);
        // Fault injection into the private index: throw after publishing, without a production test hook.
        var indexField = SecondaryStorage.class.getDeclaredField("index");
        indexField.setAccessible(true);
        var index = FileIndex.class.cast(indexField.get(storage));
        var slotsField = FileIndex.class.getDeclaredField("slots");
        slotsField.setAccessible(true);
        var failingSlots = new TreeMap<Integer, FileIndexEntry>() {
            private boolean fail = true;
            @Override
            public FileIndexEntry put(Integer slot, FileIndexEntry entry) {
                var previous = super.put(slot, entry);
                if (fail) {
                    fail = false;
                    throw new StorageException("Injected publication failure");
                }
                return previous;
            }
        };
        slotsField.set(index, failingSlots);
        assertThrows(StorageException.class, () -> storage.storeProgram("p", Collections.nCopies(32, load)));
        assertTrue(storage.entries().isEmpty());
        for (int address = 0; address < 64; address++) assertSame(EmptyStorageContent.INSTANCE, storage.read(address));
        var entry = storage.storeProgram("p", Collections.nCopies(32, store));
        assertEquals(32, entry.startAddress());
        assertTrue(storage.removeProgram("p"));
        assertEquals(32, storage.storeProgram("again", Collections.nCopies(32, load)).startAddress());
    }

    private List<StorageCell> cells(SecondaryStorage storage) {
        var result = new ArrayList<StorageCell>();
        for (int address = 0; address < storage.size(); address++) result.add(storage.cell(address));
        return List.copyOf(result);
    }
}
