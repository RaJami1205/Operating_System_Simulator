package io.github.rajami1205.osimulator.model.storage;

import io.github.rajami1205.osimulator.model.storage.exception.InvalidStorageReleaseException;
import io.github.rajami1205.osimulator.model.storage.exception.StorageAllocationException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** First-Fit acotado a [dataStart, dataEnd); admite un área vacía y no compacta. */
public final class FirstFitStorageAllocator {
    private final int dataStart;
    private final int dataEnd;
    private final TreeMap<Integer, Integer> freeBlocks = new TreeMap<>();
    private final Map<UUID, StorageAllocation> active = new HashMap<>();

    public FirstFitStorageAllocator(int dataStart, int dataEnd) {
        if (dataStart < 0 || dataEnd < dataStart) {
            throw new IllegalArgumentException("Invalid PROGRAM_DATA bounds");
        }
        this.dataStart = dataStart;
        this.dataEnd = dataEnd;
        reset();
    }

    public StorageAllocation allocate(int size) {
        if (size < 1) throw new StorageAllocationException("Allocation size must be positive");
        var block = freeBlocks.entrySet().stream().filter(entry -> entry.getValue() >= size)
                .findFirst().orElseThrow(() -> new StorageAllocationException("Insufficient contiguous storage"));
        int base = block.getKey();
        int remaining = block.getValue() - size;
        var allocation = new StorageAllocation(UUID.randomUUID(), base, size);
        freeBlocks.remove(base);
        if (remaining > 0) freeBlocks.put(base + size, remaining);
        active.put(allocation.allocationId(), allocation);
        return allocation;
    }

    public boolean isActive(StorageAllocation allocation) {
        return allocation != null && allocation.equals(active.get(allocation.allocationId()));
    }

    public void release(StorageAllocation allocation) {
        if (!isActive(allocation)) {
            throw new InvalidStorageReleaseException("Allocation is stale, foreign or already released");
        }
        active.remove(allocation.allocationId());
        int base = allocation.base();
        int size = allocation.size();
        var left = freeBlocks.lowerEntry(base);
        if (left != null && left.getKey() + left.getValue() == base) {
            base = left.getKey();
            size += left.getValue();
            freeBlocks.remove(left.getKey());
        }
        var right = freeBlocks.ceilingEntry(base);
        if (right != null && base + size == right.getKey()) {
            size += right.getValue();
            freeBlocks.remove(right.getKey());
        }
        freeBlocks.put(base, size);
    }

    /** Invalida todos los handles anteriores, incluso si sus rangos se reutilizan. */
    public void reset() {
        active.clear();
        freeBlocks.clear();
        if (dataStart < dataEnd) freeBlocks.put(dataStart, dataEnd - dataStart);
    }
}
