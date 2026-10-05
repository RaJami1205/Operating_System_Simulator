package io.github.rajami1205.osimulator.model.process.exception;

/** Señala un POP sobre un stack vacío antes de modificarlo; el engine la traduce a fallo de ejecución. */
public final class ProcessStackUnderflowException extends IllegalStateException {
    /** Conserva el diagnóstico de la condición inválida. */
    public ProcessStackUnderflowException() {
        super("Process stack is empty");
    }
}
