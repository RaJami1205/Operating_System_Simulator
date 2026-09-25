package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.memory.exception.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FirstFitMemoryAllocatorTest {
    private FirstFitMemoryAllocator allocator() {
        return new FirstFitMemoryAllocator(MemoryRegion.USER, 32, 20);
    }

    @Test
    void takesFirstSufficientBlockAndPreservesRemainder() {
        var allocator = allocator();
        var first = allocator.allocate(3);
        var second = allocator.allocate(4);
        var third = allocator.allocate(5);
        assertEquals(32, first.base());
        assertEquals(35, second.base());
        assertEquals(39, third.base());
        allocator.release(first);
        allocator.release(third);
        var larger = allocator.allocate(4);
        assertEquals(39, larger.base());
        assertEquals(32, allocator.allocate(2).base());
        assertEquals(34, allocator.allocate(1).base());
        assertEquals(43, allocator.allocate(9).base());
        assertThrows(MemoryAllocationException.class, () -> allocator.allocate(1));
        assertTrue(allocator.isActive(second));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE, 21, Integer.MAX_VALUE})
    void rejectsInvalidOrUnavailableSizesWithoutChangingFreeSpace(int size) {
        var allocator = allocator();
        assertThrows(MemoryAllocationException.class, () -> allocator.allocate(size));
        assertEquals(32, allocator.allocate(20).base());
    }

    @Test
    void fragmentationDoesNotPermitNoncontiguousAllocationOrMoveActiveBlocks() {
        var allocator = allocator();
        var a = allocator.allocate(5);
        var b = allocator.allocate(5);
        var c = allocator.allocate(5);
        var d = allocator.allocate(5);
        allocator.release(a);
        allocator.release(c);
        assertThrows(MemoryAllocationException.class, () -> allocator.allocate(6));
        assertEquals(37, b.base());
        assertEquals(47, d.base());
        assertTrue(allocator.isActive(b));
        assertTrue(allocator.isActive(d));
    }

    @ParameterizedTest
    @CsvSource({"0,1,2", "2,1,0", "0,2,1"})
    void coalescesLeftRightAndBothSides(int first, int second, int third) {
        var allocator = allocator();
        var blocks = new MemoryAllocation[]{allocator.allocate(5), allocator.allocate(5), allocator.allocate(10)};
        allocator.release(blocks[first]);
        allocator.release(blocks[second]);
        allocator.release(blocks[third]);
        var whole = allocator.allocate(20);
        assertEquals(32, whole.base());
        assertEquals(52, whole.endExclusive());
    }

    @Test
    void rejectsDoubleStaleForeignAndAlteredReleasesWithoutChangingState() {
        var allocator = allocator();
        var old = allocator.allocate(20);
        allocator.release(old);
        assertThrows(InvalidMemoryReleaseException.class, () -> allocator.release(old));
        var current = allocator.allocate(20);
        assertNotEquals(old.allocationId(), current.allocationId());
        assertThrows(InvalidMemoryReleaseException.class, () -> allocator.release(old));
        var foreign = allocator().allocate(20);
        assertThrows(InvalidMemoryReleaseException.class, () -> allocator.release(foreign));
        var wrongRegion = new MemoryAllocation(current.allocationId(), MemoryRegion.KERNEL, 32, 20);
        var wrongSize = new MemoryAllocation(current.allocationId(), MemoryRegion.USER, 32, 19);
        var forged = new MemoryAllocation(UUID.randomUUID(), MemoryRegion.USER, 32, 20);
        for (var invalid : new MemoryAllocation[]{wrongRegion, wrongSize, forged}) {
            assertThrows(InvalidMemoryReleaseException.class, () -> allocator.release(invalid));
        }
        assertThrows(InvalidMemoryReleaseException.class, () -> allocator.release(null));
        assertTrue(allocator.isActive(current));
        assertThrows(MemoryAllocationException.class, () -> allocator.allocate(1));
        allocator.release(current);
        assertEquals(20, allocator.allocate(20).size());
    }

    @Test
    void resetInvalidatesIdentitiesAndRestoresBoundsIdempotently() {
        var allocator = allocator();
        var old = allocator.allocate(20);
        allocator.reset();
        allocator.reset();
        var current = allocator.allocate(20);
        assertEquals(old.base(), current.base());
        assertFalse(allocator.isActive(old));
        assertTrue(allocator.ownsRange(32, 20));
        assertFalse(allocator.ownsRange(32, 19));
        assertThrows(InvalidMemoryReleaseException.class, () -> allocator.release(old));
    }

    @ParameterizedTest
    @CsvSource({"-1,1", "0,0", "0,-1", "2147483647,1"})
    void validatesAllocatorAndAllocationRanges(int base, int size) {
        assertThrows(IllegalArgumentException.class, () -> new FirstFitMemoryAllocator(MemoryRegion.USER, base, size));
        assertThrows(IllegalArgumentException.class, () -> new MemoryAllocation(UUID.randomUUID(), MemoryRegion.USER, base, size));
    }

    @Test
    void requiresRegionAndIdentityAndAllowsLargestEndAddress() {
        assertThrows(NullPointerException.class, () -> new FirstFitMemoryAllocator(null, 0, 1));
        assertThrows(NullPointerException.class, () -> new MemoryAllocation(null, MemoryRegion.USER, 0, 1));
        assertThrows(NullPointerException.class, () -> new MemoryAllocation(UUID.randomUUID(), null, 0, 1));
        var allocator = new FirstFitMemoryAllocator(MemoryRegion.USER, Integer.MAX_VALUE - 1, 1);
        assertEquals(Integer.MAX_VALUE, allocator.allocate(1).endExclusive());
    }
}
