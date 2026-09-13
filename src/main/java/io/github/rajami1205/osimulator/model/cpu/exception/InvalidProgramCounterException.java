package io.github.rajami1205.osimulator.model.cpu.exception;

/**
 * Indica que un valor no puede representar el Program Counter simulado.
 */
public class InvalidProgramCounterException extends IllegalArgumentException {

    // Conserva el diagnóstico de la condición inválida.
    public InvalidProgramCounterException(String message) {
        super(message);
    }
}
