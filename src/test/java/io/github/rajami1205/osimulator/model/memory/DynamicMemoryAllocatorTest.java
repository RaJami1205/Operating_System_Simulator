package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryReleaseException;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryAllocationException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class DynamicMemoryAllocatorTest {
    private DynamicMemoryAllocator allocator(UserAllocationStrategy strategy) {
        return new DynamicMemoryAllocator(MemoryRegion.USER, 32, 40, strategy);
    }

    @Test void strategiesChooseDifferentHolesAndBestFitBreaksTiesByAddress() {
        for (var strategy : UserAllocationStrategy.values()) {
            var a = allocator(strategy);
            var large = a.allocate(10); a.allocate(2);
            var small = a.allocate(6); a.allocate(2);
            var equal = a.allocate(6); a.allocate(2);
            a.release(large); a.release(small); a.release(equal);
            int expected = switch (strategy) {
                case FIRST_FIT -> 32;
                case NEXT_FIT -> 60;
                case BEST_FIT -> 44;
            };
            assertEquals(expected, a.allocate(5).base());
        }
    }

    @Test void nextFitSplitsBothSidesOfCursorAndPreservesAllFreeCells() {
        var a = allocator(UserAllocationStrategy.NEXT_FIT);
        var old = a.allocate(10);
        a.release(old); // Cursor 42 dentro del hueco coalescido [32,72).
        var middle = a.allocate(5);
        assertEquals(42, middle.base());
        assertEquals(47, a.allocate(25).base()); // Fin exacto normaliza a 32.
        assertEquals(32, a.allocate(10).base());
        assertThrows(MemoryAllocationException.class, () -> a.allocate(1));
        assertTrue(a.isActive(middle));
    }

    @Test void nextFitReconsidersWholeHoleWhenCursorSuffixIsTooSmall() {
        var a = allocator(UserAllocationStrategy.NEXT_FIT);
        var old = a.allocate(30);
        a.release(old); // Sufijo 10; hueco completo 40.
        assertEquals(32, a.allocate(35).base());
        assertEquals(67, a.allocate(5).base());
    }

    @Test void nextFitWrapsPastOccupiedCursorAndReleaseDoesNotRewind() {
        var a = allocator(UserAllocationStrategy.NEXT_FIT);
        var first = a.allocate(10); var second = a.allocate(10);
        a.allocate(20); // Cursor 32, ocupado.
        a.release(second);
        assertEquals(42, a.allocate(8).base());
        a.release(first);
        assertEquals(50, a.allocate(2).base());
        assertEquals(32, a.allocate(10).base());
    }

    @ParameterizedTest @EnumSource(UserAllocationStrategy.class)
    void fragmentationCoalescingAndHandleValidation(UserAllocationStrategy strategy) {
        var a = allocator(strategy);
        var left = a.allocate(10); var middle = a.allocate(10);
        var right = a.allocate(10); var tail = a.allocate(10);
        a.release(left); a.release(right);
        assertThrows(MemoryAllocationException.class, () -> a.allocate(11));
        assertThrows(InvalidMemoryReleaseException.class, () -> a.release(left));
        assertThrows(InvalidMemoryReleaseException.class, () -> a.release(
                new MemoryAllocation(UUID.randomUUID(), MemoryRegion.USER, middle.base(), middle.size())));
        assertThrows(InvalidMemoryReleaseException.class, () -> a.release(
                new MemoryAllocation(middle.allocationId(), MemoryRegion.USER, middle.base(), 9)));
        a.release(middle); // Coalescing bilateral.
        assertEquals(32, a.allocate(30).base());
        assertTrue(a.isActive(tail));
        assertTrue(a.ownsRange(tail.base(), tail.size()));
        a.reset();
        assertFalse(a.isActive(tail));
        assertThrows(InvalidMemoryReleaseException.class, () -> a.release(tail));
        assertEquals(32, a.allocate(40).base());
    }

    @Test void invalidRequestsDoNotAdvanceCursorAndBoundsNeverOverflow() {
        var a = allocator(UserAllocationStrategy.NEXT_FIT);
        a.allocate(7);
        for (int size : new int[]{0, -1, 34, Integer.MAX_VALUE}) {
            assertThrows(MemoryAllocationException.class, () -> a.allocate(size));
        }
        assertEquals(39, a.allocate(1).base());
        assertThrows(IllegalArgumentException.class, () -> new DynamicMemoryAllocator(
                MemoryRegion.USER, Integer.MAX_VALUE, 1, UserAllocationStrategy.NEXT_FIT));
        assertThrows(NullPointerException.class, () -> new DynamicMemoryAllocator(MemoryRegion.USER, 0, 10, null));
        var high = new DynamicMemoryAllocator(MemoryRegion.USER, Integer.MAX_VALUE - 10, 10, UserAllocationStrategy.NEXT_FIT);
        var all = high.allocate(10);
        assertEquals(Integer.MAX_VALUE, all.endExclusive());
        high.release(all);
        assertEquals(Integer.MAX_VALUE - 10, high.allocate(10).base());
    }
}
