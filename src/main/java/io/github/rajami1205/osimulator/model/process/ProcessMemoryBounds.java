package io.github.rajami1205.osimulator.model.process;

import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessConfigurationException;

/** Región asignada [base, base + limit); limit es su cantidad de posiciones. */
public record ProcessMemoryBounds(int base, int limit) {
    /** Exige Base no negativa y Limit positivo sin overflow; Limit es cantidad, no dirección final. */
    public ProcessMemoryBounds {
        if (base < 0 || limit < 1 || (long) base + limit > Integer.MAX_VALUE) {
            throw new InvalidProcessConfigurationException("Invalid process memory bounds: " + base + ", " + limit);
        }
    }

    /** Calcula el fin físico exclusivo Base + Limit; también es el marcador terminal físico. */
    public int endExclusive() {
        return base + limit;
    }
}
