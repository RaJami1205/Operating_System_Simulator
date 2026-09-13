package io.github.rajami1205.osimulator.model.cpu.exception;

/**
 * Indica que un valor está fuera del rango lógico de un registro de datos simulado.
 */
public class InvalidRegisterValueException extends IllegalArgumentException {

    // Conserva el diagnóstico de la condición inválida.
    public InvalidRegisterValueException(String message) {
        super(message);
    }
}
