package io.github.rajami1205.osimulator.model.process;

/** Dirección simulada; no implica que exista todavía un PCB residente en Kernel. */
public record PcbAddress(int address) {
    public PcbAddress {
        if (address < 0) {
            throw new IllegalArgumentException("PCB address must not be negative: " + address);
        }
    }
}
