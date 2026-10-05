package io.github.rajami1205.osimulator.model.storage;

import io.github.rajami1205.osimulator.model.storage.exception.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FirstFitStorageAllocatorTest {
    @Test
    void allocatesExactFirstFitAndPreservesRemaindersAndActiveAddresses() {
        var allocator = new FirstFitStorageAllocator(10, 30);
        var a = allocator.allocate(3);
        var b = allocator.allocate(4);
        var c = allocator.allocate(5);
        assertEquals(10, a.base());
        assertEquals(13, b.base());
        assertEquals(17, c.base());
        allocator.release(a);
        allocator.release(c);
        assertEquals(17, allocator.allocate(4).base());
        assertEquals(10, allocator.allocate(2).base());
        assertEquals(12, allocator.allocate(1).base());
        assertEquals(21, allocator.allocate(9).base());
        assertThrows(StorageAllocationException.class, () -> allocator.allocate(1));
        assertTrue(allocator.isActive(b));
        assertEquals(13, b.base());
    }

    @Test
    void rejectsFragmentationWithoutCompacting() {
        var allocator = new FirstFitStorageAllocator(10, 30);
        var a = allocator.allocate(5);
        var b = allocator.allocate(5);
        var c = allocator.allocate(5);
        var d = allocator.allocate(5);
        allocator.release(a);
        allocator.release(c);
        assertThrows(StorageAllocationException.class, () -> allocator.allocate(6));
        assertTrue(allocator.isActive(b));
        assertTrue(allocator.isActive(d));
        assertEquals(10, allocator.allocate(5).base());
        assertEquals(20, allocator.allocate(5).base());
    }

    @ParameterizedTest
    @CsvSource({"0,1,2", "2,1,0", "0,2,1"})
    void coalescesLeftRightAndBothSides(int first, int second, int third) {
        var allocator = new FirstFitStorageAllocator(20, 40);
        var blocks = new StorageAllocation[]{allocator.allocate(5), allocator.allocate(5), allocator.allocate(10)};
        allocator.release(blocks[first]);
        allocator.release(blocks[second]);
        allocator.release(blocks[third]);
        var all = allocator.allocate(20);
        assertEquals(20, all.base());
        assertEquals(40, all.endExclusive());
    }

    @Test
    void validatesIdentityOnDoubleStaleForeignAndAlteredRelease() {
        var allocator = new FirstFitStorageAllocator(0, 5);
        var old = allocator.allocate(5);
        allocator.release(old);
        assertThrows(InvalidStorageReleaseException.class, () -> allocator.release(old));
        var current = allocator.allocate(5);
        assertNotEquals(old.allocationId(), current.allocationId());
        var foreign = new FirstFitStorageAllocator(0, 5).allocate(5);
        for (var invalid : new StorageAllocation[]{old, foreign,
                new StorageAllocation(current.allocationId(), 0, 4),
                new StorageAllocation(current.allocationId(), 1, 5),
                new StorageAllocation(UUID.randomUUID(), 0, 5)}) {
            assertThrows(InvalidStorageReleaseException.class, () -> allocator.release(invalid));
        }
        assertThrows(InvalidStorageReleaseException.class, () -> allocator.release(null));
        assertTrue(allocator.isActive(current));
        assertThrows(StorageAllocationException.class, () -> allocator.allocate(1));
        allocator.reset();
        allocator.reset();
        var reused = allocator.allocate(5);
        assertFalse(allocator.isActive(current));
        assertNotEquals(current.allocationId(), reused.allocationId());
        assertThrows(InvalidStorageReleaseException.class, () -> allocator.release(current));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10, Integer.MAX_VALUE})
    void emptyBoundsInitializeAndResetButCannotAllocate(int address) {
        var allocator = new FirstFitStorageAllocator(address, address);
        assertThrows(StorageAllocationException.class, () -> allocator.allocate(1));
        allocator.reset();
        assertThrows(StorageAllocationException.class, () -> allocator.allocate(1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE, 6, Integer.MAX_VALUE})
    void rejectsBadRequestsWithoutLosingSpace(int size) {
        var allocator = new FirstFitStorageAllocator(5, 10);
        assertThrows(StorageAllocationException.class, () -> allocator.allocate(size));
        assertEquals(5, allocator.allocate(5).base());
    }

    @Test
    void validatesBoundsAndAllocationValues() {
        assertThrows(IllegalArgumentException.class, () -> new FirstFitStorageAllocator(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new FirstFitStorageAllocator(1, 0));
        assertThrows(NullPointerException.class, () -> new StorageAllocation(null, 0, 1));
        for (int[] range : new int[][]{{-1, 1}, {0, 0}, {0, -1}, {Integer.MAX_VALUE, 1}}) {
            assertThrows(IllegalArgumentException.class, () -> new StorageAllocation(UUID.randomUUID(), range[0], range[1]));
        }
        var allocator = new FirstFitStorageAllocator(Integer.MAX_VALUE - 1, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, allocator.allocate(1).endExclusive());
    }
}
