package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.memory.exception.IncompatibleProgramException;
import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryReleaseException;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryAllocationException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Una ocupación por partición; el handle autoriza únicamente el prefijo ejecutable. */
public final class FixedPartitionMemoryAllocator implements MemoryAllocator {
    private final List<FixedPartition> layout;
    private final Map<Integer, MemoryAllocation> active = new HashMap<>();
    private final int maximum;

    public FixedPartitionMemoryAllocator(MemoryConfiguration geometry, FixedPartitionConfiguration configuration) {
        layout = Objects.requireNonNull(configuration).layout(geometry);
        maximum = layout.stream().mapToInt(FixedPartition::capacity).max().orElseThrow();
    }

    public void validateProgramLength(int length) {
        if (length <= 0) throw new MemoryAllocationException("Allocation size must be positive");
        if (length > maximum) throw new IncompatibleProgramException(length, maximum);
    }

    @Override public MemoryAllocation allocate(int size) {
        validateProgramLength(size);
        var partition = layout.stream().filter(p -> p.capacity() >= size && !active.containsKey(p.base()))
                .min(Comparator.comparingInt(FixedPartition::capacity).thenComparingInt(FixedPartition::base))
                .orElseThrow(() -> new MemoryAllocationException("All compatible partitions are occupied"));
        var allocation = new MemoryAllocation(UUID.randomUUID(), MemoryRegion.USER, partition.base(), size);
        active.put(partition.base(), allocation);
        return allocation;
    }

    @Override public boolean isActive(MemoryAllocation allocation) {
        return allocation != null && allocation.equals(active.get(allocation.base()));
    }

    @Override public boolean ownsRange(int base, int size) {
        var allocation = active.get(base);
        return allocation != null && allocation.size() == size;
    }

    @Override public void release(MemoryAllocation allocation) {
        if (!isActive(allocation)) throw new InvalidMemoryReleaseException("Allocation is stale, foreign or already released");
        active.remove(allocation.base());
    }

    @Override public void reset() { active.clear(); }

    public List<FixedPartition.Occupancy> partitions() {
        return layout.stream().map(p -> new FixedPartition.Occupancy(p, Optional.ofNullable(active.get(p.base())))).toList();
    }
}
