package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.memory.exception.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FixedPartitionMemoryAllocatorTest {
    private FixedPartitionMemoryAllocator allocator() {
        return new FixedPartitionMemoryAllocator(new MemoryConfiguration(128, 32),
                FixedPartitionConfiguration.unequal(List.of(48, 16, 16, 16)));
    }

    @Test void selectsSmallestSuitableThenLowestBaseAndOccupiesEntirePartition() {
        var a = allocator();
        var prefix = a.allocate(3);
        assertEquals(80, prefix.base()); assertEquals(3, prefix.size());
        var state = a.partitions().get(1);
        assertTrue(state.occupied()); assertEquals(3, state.usedLength()); assertEquals(13, state.internalFragmentation());
        assertEquals(96, a.allocate(1).base()); assertEquals(112, a.allocate(16).base());
        assertEquals(32, a.allocate(2).base());
        assertThrows(MemoryAllocationException.class, () -> a.allocate(1));
        a.release(prefix);
        assertEquals(80, a.allocate(16).base()); // Prefijo liberó la partición completa.
        assertEquals(3, state.usedLength()); // Observación histórica inmutable.
        assertTrue(a.partitions().stream().allMatch(FixedPartition.Occupancy::occupied));
    }

    @Test void distinguishesImpossibleFromOccupiedAndNeverCoalescesAdjacentFreePartitions() {
        var a = allocator(); var big = a.allocate(48);
        assertThrows(IncompatibleProgramException.class, () -> a.allocate(49));
        assertThrows(MemoryAllocationException.class, () -> a.allocate(32)); // 48 libres agregados no forman una partición.
        a.release(big); assertEquals(32, a.allocate(48).base());
        for (int size : new int[]{0, -1}) assertThrows(MemoryAllocationException.class, () -> a.allocate(size));
    }

    @Test void validatesFullHandleAndResetInvalidatesIdentityWithoutChangingLayout() {
        var a = allocator(); var handle = a.allocate(7); var layout = a.partitions();
        assertTrue(a.isActive(handle)); assertTrue(a.ownsRange(80, 7)); assertFalse(a.ownsRange(80, 16));
        for (var invalid : List.of(new MemoryAllocation(UUID.randomUUID(), MemoryRegion.USER, 80, 7),
                new MemoryAllocation(handle.allocationId(), MemoryRegion.USER, 80, 8),
                new MemoryAllocation(handle.allocationId(), MemoryRegion.KERNEL, 80, 7),
                new MemoryAllocation(handle.allocationId(), MemoryRegion.USER, 81, 7), allocator().allocate(7))) {
            assertFalse(a.isActive(invalid)); assertThrows(InvalidMemoryReleaseException.class, () -> a.release(invalid));
        }
        a.release(handle); assertThrows(InvalidMemoryReleaseException.class, () -> a.release(handle));
        var next = a.allocate(7); assertNotEquals(handle.allocationId(), next.allocationId());
        a.reset(); assertFalse(a.isActive(next)); assertThrows(InvalidMemoryReleaseException.class, () -> a.release(next));
        assertEquals(layout.stream().map(FixedPartition.Occupancy::partition).toList(), a.partitions().stream().map(FixedPartition.Occupancy::partition).toList());
        assertTrue(a.partitions().stream().noneMatch(FixedPartition.Occupancy::occupied));
        assertThrows(UnsupportedOperationException.class, () -> a.partitions().clear());
    }
}
