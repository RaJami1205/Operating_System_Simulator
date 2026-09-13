package io.github.rajami1205.osimulator.model.instruction.exception;

/**
 * Indica que un operando inmediato está fuera del rango lógico de datos admitido.
 */
public class InvalidImmediateValueException extends IllegalArgumentException {

    // Conserva el diagnóstico de la condición inválida.
    public InvalidImmediateValueException(String message) {
        super(message);
    }
}
