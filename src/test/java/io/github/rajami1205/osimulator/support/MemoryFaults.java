package io.github.rajami1205.osimulator.support;

import io.github.rajami1205.osimulator.model.memory.*;

/** Fault injection at the existing allocator boundary, without production test hooks. */
public final class MemoryFaults implements MemoryAllocator {
    private final MemoryAllocator delegate;
    public RuntimeException writeFailure;
    public RuntimeException releaseFailure;
    public int releases;

    private MemoryFaults(MemoryAllocator delegate) { this.delegate = delegate; }

    public static MemoryFaults install(MainMemory memory, String region) throws Exception {
        var field = MainMemory.class.getDeclaredField(region);
        field.setAccessible(true);
        var faults = new MemoryFaults((MemoryAllocator) field.get(memory));
        field.set(memory, faults);
        return faults;
    }

    public MemoryAllocation allocate(int size) { return delegate.allocate(size); }
    public boolean isActive(MemoryAllocation allocation) {
        if (writeFailure != null) throw writeFailure;
        return delegate.isActive(allocation);
    }
    public boolean ownsRange(int base, int size) { return delegate.ownsRange(base, size); }
    public void release(MemoryAllocation allocation) {
        releases++;
        if (releaseFailure != null) throw releaseFailure;
        delegate.release(allocation);
    }
    public void reset() { delegate.reset(); }
}
