package io.github.rajami1205.osimulator.model.process.exception;

/**
 * Indica que el Program Counter guardado está fuera del rango del programa del proceso.
 */
public final class InvalidProcessProgramCounterException extends IllegalArgumentException {

    // Conserva el diagnóstico de la condición inválida.
    public InvalidProcessProgramCounterException(String message) {
        super(message);
    }
}
