package io.github.rajami1205.osimulator.model.memory.exception;

/**
 * Indica que una operación de memoria apunta a una región válida pero protegida.
 */
public class MemoryProtectionException extends RuntimeException {

    // Conserva el diagnóstico de la condición inválida.
    public MemoryProtectionException(String message) {
        super(message);
    }
}
