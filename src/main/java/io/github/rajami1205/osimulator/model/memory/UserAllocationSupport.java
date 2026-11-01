package io.github.rajami1205.osimulator.model.memory;

import java.util.List;

/** Adaptador interno: no publica checkpoints que puedan saltarse los tokens de MainMemory. */
final class UserAllocationSupport {
    private final MemoryAllocator allocator;
    UserAllocationSupport(MemoryAllocator allocator) { this.allocator = allocator; }

    Runnable captureRollback() {
        return allocator instanceof DynamicMemoryAllocator dynamic ? dynamic.captureRollback() : () -> {};
    }
    void validateProgramLength(int length) {
        if (allocator instanceof FixedPartitionMemoryAllocator fixed) fixed.validateProgramLength(length);
    }
    List<FixedPartition.Occupancy> partitions() {
        return allocator instanceof FixedPartitionMemoryAllocator fixed ? fixed.partitions() : List.of();
    }
}
