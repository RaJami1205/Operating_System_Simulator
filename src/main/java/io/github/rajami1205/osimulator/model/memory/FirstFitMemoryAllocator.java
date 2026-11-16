package io.github.rajami1205.osimulator.model.memory;

/** Fachada compatible de First-Fit; ownership, split y coalescing pertenecen al núcleo común. */
public final class FirstFitMemoryAllocator implements MemoryAllocator {
    private final DynamicMemoryAllocator delegate;

    public FirstFitMemoryAllocator(MemoryRegion region, int base, int size) {
        delegate = new DynamicMemoryAllocator(region, base, size, UserAllocationStrategy.FIRST_FIT);
    }

    @Override public MemoryAllocation allocate(int size) { return delegate.allocate(size); }
    @Override public void release(MemoryAllocation allocation) { delegate.release(allocation); }
    @Override public boolean isActive(MemoryAllocation allocation) { return delegate.isActive(allocation); }
    @Override public boolean ownsRange(int base, int size) { return delegate.ownsRange(base, size); }
    @Override public void reset() { delegate.reset(); }
}
