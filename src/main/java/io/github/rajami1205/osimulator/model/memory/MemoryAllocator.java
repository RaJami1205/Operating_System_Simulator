package io.github.rajami1205.osimulator.model.memory;

/** Administra reservas activas dentro de una única región acotada. */
public interface MemoryAllocator {
    /** Reserva un bloque contiguo del tamaño positivo solicitado o falla por tamaño/capacidad insuficiente. */
    MemoryAllocation allocate(int size);
    /** Libera una reserva activa del allocator; rechaza handles ajenos, stale o ya liberados. */
    void release(MemoryAllocation allocation);
    /** Comprueba identidad y metadata contra el conjunto de reservas activas. */
    boolean isActive(MemoryAllocation allocation);
    /** Comprueba coincidencia exacta de Base/tamaño; no acredita la identidad histórica de un handle. */
    boolean ownsRange(int base, int size);
    /** Descarta todas las reservas activas y restituye el espacio libre dentro de sus límites. */
    void reset();
}
