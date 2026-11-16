package io.github.rajami1205.osimulator.model.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Especificación fija: Equal usa count; Unequal usa capacidades en orden físico. */
public record FixedPartitionConfiguration(MemoryMode mode, int partitionCount, List<Integer> capacities) {
    public FixedPartitionConfiguration {
        Objects.requireNonNull(mode);
        capacities = List.copyOf(capacities);
        if ((mode != MemoryMode.FIXED_EQUAL && mode != MemoryMode.FIXED_UNEQUAL) || partitionCount <= 0) {
            throw new IllegalArgumentException("Fixed configuration requires a fixed mode and positive count");
        }
        if (mode == MemoryMode.FIXED_EQUAL && !capacities.isEmpty()) {
            throw new IllegalArgumentException("Equal partitions specify only their count");
        }
        if (mode == MemoryMode.FIXED_UNEQUAL) {
            if (capacities.size() != partitionCount) throw new IllegalArgumentException("Partition count mismatch");
            long total = 0;
            for (int capacity : capacities) {
                if (capacity <= 0) throw new IllegalArgumentException("Partition capacities must be positive");
                total += capacity;
                if (total > Integer.MAX_VALUE) throw new IllegalArgumentException("Partition capacity sum overflows");
            }
        }
    }

    public static FixedPartitionConfiguration equal(int count) {
        return new FixedPartitionConfiguration(MemoryMode.FIXED_EQUAL, count, List.of());
    }

    public static FixedPartitionConfiguration unequal(List<Integer> capacities) {
        return new FixedPartitionConfiguration(MemoryMode.FIXED_UNEQUAL, capacities.size(), capacities);
    }

    /** Valida contra USER antes de crear recursos y deriva bases sin huecos ni overlaps. */
    public List<FixedPartition> layout(MemoryConfiguration geometry) {
        Objects.requireNonNull(geometry);
        int userSize = geometry.userPositions();
        if (partitionCount > userSize) throw new IllegalArgumentException("Partition count exceeds USER size");
        if (mode == MemoryMode.FIXED_EQUAL && userSize % partitionCount != 0) {
            throw new IllegalArgumentException("USER size must be exactly divisible by partition count");
        }
        long total = mode == MemoryMode.FIXED_EQUAL ? userSize : capacities.stream().mapToLong(Integer::longValue).sum();
        if (total != userSize) throw new IllegalArgumentException("Partition capacities must sum to USER size: " + userSize);
        var result = new ArrayList<FixedPartition>(partitionCount);
        long base = geometry.userStartAddress();
        for (int i = 0; i < partitionCount; i++) {
            int capacity = mode == MemoryMode.FIXED_EQUAL ? userSize / partitionCount : capacities.get(i);
            result.add(new FixedPartition(i + 1, Math.toIntExact(base), capacity));
            base += capacity;
        }
        return List.copyOf(result);
    }

    public static void validateMode(MemoryConfiguration geometry, MemoryMode mode,
            Optional<FixedPartitionConfiguration> fixed) {
        Objects.requireNonNull(geometry); Objects.requireNonNull(mode); Objects.requireNonNull(fixed);
        if (mode == MemoryMode.DYNAMIC || mode == MemoryMode.PAGING) {
            if (fixed.isPresent()) throw new IllegalArgumentException("Dynamic memory cannot have a fixed layout");
        } else {
            var configuration = fixed.orElseThrow(() -> new IllegalArgumentException("Fixed memory requires a layout"));
            if (configuration.mode() != mode) throw new IllegalArgumentException("Memory mode and fixed layout disagree");
            configuration.layout(geometry);
        }
    }
}
