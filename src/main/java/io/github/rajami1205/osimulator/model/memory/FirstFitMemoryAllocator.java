package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryReleaseException;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryAllocationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** First-Fit por dirección ascendente, sin mover reservas activas. */
public final class FirstFitMemoryAllocator implements MemoryAllocator {
    private final MemoryRegion region;
    private final int base;
    private final int size;
    private final TreeMap<Integer, Integer> freeBlocks = new TreeMap<>();
    private final Map<UUID, MemoryAllocation> active = new HashMap<>();

    public FirstFitMemoryAllocator(MemoryRegion region, int base, int size) {
        this.region = Objects.requireNonNull(region, "region must not be null");
        if (base < 0 || size <= 0 || (long) base + size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid allocator bounds");
        }
        this.base = base;
        this.size = size;
        reset();
    }

    @Override
    public MemoryAllocation allocate(int requestedSize) {
        if (requestedSize <= 0) throw new MemoryAllocationException("Allocation size must be positive");
        var block = freeBlocks.entrySet().stream()
                .filter(entry -> entry.getValue() >= requestedSize).findFirst()
                .orElseThrow(() -> new MemoryAllocationException("No contiguous block fits size " + requestedSize));
        int start = block.getKey();
        int remaining = block.getValue() - requestedSize;
        var allocation = new MemoryAllocation(UUID.randomUUID(), region, start, requestedSize);
        freeBlocks.remove(start);
        if (remaining > 0) freeBlocks.put(start + requestedSize, remaining);
        active.put(allocation.allocationId(), allocation);
        return allocation;
    }

    @Override
    public boolean isActive(MemoryAllocation allocation) {
        return allocation != null && allocation.equals(active.get(allocation.allocationId()));
    }

    @Override
    public boolean ownsRange(int base, int size) {
        return active.values().stream().anyMatch(a -> a.base() == base && a.size() == size);
    }

    @Override
    public void release(MemoryAllocation allocation) {
        if (!isActive(allocation)) {
            throw new InvalidMemoryReleaseException("Allocation is stale, foreign or already released");
        }
        active.remove(allocation.allocationId());
        int start = allocation.base();
        int length = allocation.size();
        var left = freeBlocks.lowerEntry(start);
        if (left != null && left.getKey() + left.getValue() == start) {
            start = left.getKey();
            length += left.getValue();
            freeBlocks.remove(left.getKey());
        }
        var right = freeBlocks.ceilingEntry(start);
        if (right != null && start + length == right.getKey()) {
            length += right.getValue();
            freeBlocks.remove(right.getKey());
        }
        freeBlocks.put(start, length);
    }

    /** Invalida todas las identidades previas, incluso si se reutilizan sus direcciones. */
    @Override
    public void reset() {
        active.clear();
        freeBlocks.clear();
        freeBlocks.put(base, size);
    }
}
