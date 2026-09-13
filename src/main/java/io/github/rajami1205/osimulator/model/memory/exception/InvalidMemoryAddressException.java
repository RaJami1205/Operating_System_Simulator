package io.github.rajami1205.osimulator.model.memory.exception;

/**
 * Indica que una dirección está fuera del espacio de memoria simulada configurado.
 */
public class InvalidMemoryAddressException extends IndexOutOfBoundsException {

    // Conserva el diagnóstico de la condición inválida.
    public InvalidMemoryAddressException(String message) {
        super(message);
    }
}
