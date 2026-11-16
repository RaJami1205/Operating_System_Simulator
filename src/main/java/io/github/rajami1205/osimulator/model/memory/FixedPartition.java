package io.github.rajami1205.osimulator.model.memory;

import java.util.Objects;
import java.util.Optional;

/** Geometría inmutable; la capacidad no amplía el rango del handle ejecutable. */
public record FixedPartition(int id, int base, int capacity) {
    public FixedPartition {
        if (id < 1 || base < 0 || capacity <= 0 || (long) base + capacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid fixed partition bounds");
        }
    }

    /** Copia de ocupación sin PID ni referencias al allocator vivo. */
    public record Occupancy(FixedPartition partition, Optional<MemoryAllocation> allocation) {
        public Occupancy {
            Objects.requireNonNull(partition);
            Objects.requireNonNull(allocation);
            allocation.ifPresent(handle -> {
                if (handle.region() != MemoryRegion.USER || handle.base() != partition.base()
                        || handle.size() > partition.capacity()) {
                    throw new IllegalArgumentException("Partition and allocation disagree");
                }
            });
        }
        public boolean occupied() { return allocation.isPresent(); }
        public int usedLength() { return allocation.map(MemoryAllocation::size).orElse(0); }
        public int internalFragmentation() { return occupied() ? partition.capacity() - usedLength() : 0; }
    }
}
