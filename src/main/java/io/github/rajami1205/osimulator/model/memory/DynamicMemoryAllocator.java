package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryReleaseException;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryAllocationException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Núcleo contiguo compartido: la estrategia sólo selecciona el rango, nunca su ownership. */
public final class DynamicMemoryAllocator implements MemoryAllocator {
    private final MemoryRegion region;
    private final int base;
    private final int size;
    private final UserAllocationStrategy strategy;
    private final TreeMap<Integer, Integer> freeBlocks = new TreeMap<>();
    private final Map<UUID, MemoryAllocation> active = new HashMap<>();
    private int cursor;

    public DynamicMemoryAllocator(MemoryRegion region, int base, int size, UserAllocationStrategy strategy) {
        this.region = Objects.requireNonNull(region, "region must not be null");
        this.strategy = Objects.requireNonNull(strategy, "strategy must not be null");
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
        int start = select(requestedSize);
        if (start < 0) throw new MemoryAllocationException("No contiguous block fits size " + requestedSize);
        var block = freeBlocks.floorEntry(start);
        int blockBase = block.getKey();
        int blockEnd = blockBase + block.getValue();
        var allocation = new MemoryAllocation(UUID.randomUUID(), region, start, requestedSize);
        freeBlocks.remove(blockBase);
        if (blockBase < start) freeBlocks.put(blockBase, start - blockBase);
        if (allocation.endExclusive() < blockEnd) {
            freeBlocks.put(allocation.endExclusive(), blockEnd - allocation.endExclusive());
        }
        active.put(allocation.allocationId(), allocation);
        if (strategy == UserAllocationStrategy.NEXT_FIT) {
            cursor = allocation.endExclusive() == base + size ? base : allocation.endExclusive();
        }
        return allocation;
    }

    private int select(int requestedSize) {
        if (strategy == UserAllocationStrategy.NEXT_FIT) {
            // Un bloque que contiene el cursor aporta primero sólo su sufijo.
            for (var block : freeBlocks.entrySet()) {
                int start = Math.max(cursor, block.getKey());
                if (block.getKey() + block.getValue() - start >= requestedSize) return start;
            }
            // Tras wrap se reconsidera completo el bloque atravesado por el cursor.
            for (var block : freeBlocks.headMap(cursor, false).entrySet()) {
                if (block.getValue() >= requestedSize) return block.getKey();
            }
            return -1;
        }
        int selected = -1;
        int bestSize = Integer.MAX_VALUE;
        for (var block : freeBlocks.entrySet()) {
            if (block.getValue() >= requestedSize && (selected < 0 || block.getValue() < bestSize)) {
                selected = block.getKey();
                bestSize = block.getValue();
                if (strategy == UserAllocationStrategy.FIRST_FIT) return selected;
            }
        }
        return selected;
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

    @Override
    public void reset() {
        active.clear();
        freeBlocks.clear();
        freeBlocks.put(base, size);
        cursor = base;
    }

    Runnable captureRollback() {
        int previous = cursor;
        return () -> cursor = previous;
    }
}
