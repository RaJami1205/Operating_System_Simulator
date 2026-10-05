package io.github.rajami1205.osimulator.model.execution;

/**
 * Cuenta ticks exitosos de la sesión, independientemente del wall clock; no representa timestamps ni
 * elapsed real.
 */
public final class CpuTickCounter {
    private long ticks;
    /** Consulta el total de ticks exitosos desde Initialize; no avanza ni consulta wall clock. */
    public long current() { return ticks; }
    /** Detecta overflow antes de ejecutar efectos semánticos; no modifica el contador. */
    public void validateCanAdvance() { Math.incrementExact(ticks); }
    /** Incrementa exactamente un tick exitoso; Math.incrementExact rechaza overflow. */
    public void advance() { ticks = Math.incrementExact(ticks); }
}
