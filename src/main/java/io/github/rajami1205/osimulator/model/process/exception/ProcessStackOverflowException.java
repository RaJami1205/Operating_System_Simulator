package io.github.rajami1205.osimulator.model.process.exception;

/**
 * Señala que una inserción excedería las cinco posiciones del stack; el engine la traduce a fallo de
 * ejecución.
 */
public final class ProcessStackOverflowException extends IllegalStateException {
    /** Conserva el diagnóstico de la condición inválida. */
    public ProcessStackOverflowException() {
        super("Process stack is full");
    }
}
