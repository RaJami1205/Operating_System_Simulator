package io.github.rajami1205.osimulator.model.memory;

/** Dirección física no negativa; no acredita residencia ni autoriza acceso a memoria. */
public record PhysicalAddress(int value) {
    public PhysicalAddress {
        if (value < 0) throw new IllegalArgumentException("Physical address must not be negative");
    }
}
