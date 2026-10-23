package io.github.rajami1205.osimulator.model.process;

/** Base simulada del bloque Kernel completo; no es un offset ni acredita residencia por sí sola. */
public record PcbAddress(int address) {
    /** Rechaza direcciones simuladas negativas; no comprueba residencia Kernel. */
    public PcbAddress {
        if (address < 0) {
            throw new IllegalArgumentException("PCB address must not be negative: " + address);
        }
    }
}
