package io.github.rajami1205.osimulator.model.memory;

/** Administra reservas activas dentro de una única región acotada. */
public interface MemoryAllocator {
    MemoryAllocation allocate(int size);
    void release(MemoryAllocation allocation);
    boolean isActive(MemoryAllocation allocation);
    boolean ownsRange(int base, int size);
    void reset();
}
