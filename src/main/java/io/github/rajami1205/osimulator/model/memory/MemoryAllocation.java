package io.github.rajami1205.osimulator.model.memory;

import java.util.Objects;
import java.util.UUID;

/** Reserva identificada independientemente de la reutilización de su rango físico. */
public record MemoryAllocation(UUID allocationId, MemoryRegion region, int base, int size) {
    /** Exige identidad/región no nulas y un rango positivo sin overflow de dirección. */
    public MemoryAllocation {
        Objects.requireNonNull(allocationId, "allocationId must not be null");
        Objects.requireNonNull(region, "region must not be null");
        if (base < 0 || size <= 0 || (long) base + size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid allocation range");
        }
    }

    /** Calcula el fin físico exclusivo de esta reserva, sin comprobar que su identidad siga activa. */
    public int endExclusive() { return base + size; }
}
