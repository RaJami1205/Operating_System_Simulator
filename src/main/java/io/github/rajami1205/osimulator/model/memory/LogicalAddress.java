package io.github.rajami1205.osimulator.model.memory;

/** Dirección lógica no negativa; su pertenencia a código ejecutable depende del rango. */
public record LogicalAddress(int value) {
    public LogicalAddress {
        if (value < 0) throw new IllegalArgumentException("Logical address must not be negative");
    }
}
