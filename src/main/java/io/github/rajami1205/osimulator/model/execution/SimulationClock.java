package io.github.rajami1205.osimulator.model.execution;

/** Tiempo global de sesión en segundos lógicos, independiente del trabajo de CPU y del reloj real. */
public final class SimulationClock {
    private long seconds;

    public long current() { return seconds; }

    /** Comprueba capacidad antes de los efectos del intervalo, sin consumir tiempo. */
    public void validateCanAdvance() { Math.incrementExact(seconds); }

    /** Confirma un intervalo global; el coordinador del runtime es su único escritor. */
    public void advance() { seconds = Math.incrementExact(seconds); }
}
