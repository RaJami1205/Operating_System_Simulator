package io.github.rajami1205.osimulator.model.storage;

import java.util.Objects;
import java.util.UUID;

/** Identidad estable de una reserva, independiente de la reutilización de su rango. */
public record StorageAllocation(UUID allocationId, int base, int size) {
    /** Exige identidad no nula y rango físico positivo sin overflow de dirección. */
    public StorageAllocation {
        Objects.requireNonNull(allocationId, "allocationId must not be null");
        if (base < 0 || size < 1 || (long) base + size > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid storage allocation range");
        }
    }

    /** Calcula el fin físico exclusivo de la reserva; su validez activa pertenece al allocator. */
    public int endExclusive() { return base + size; }
}
