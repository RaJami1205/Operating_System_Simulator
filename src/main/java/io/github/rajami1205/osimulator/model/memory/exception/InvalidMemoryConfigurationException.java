package io.github.rajami1205.osimulator.model.memory.exception;

/**
 * Indica que una configuración de memoria simulada incumple sus invariantes.
 */
public class InvalidMemoryConfigurationException extends IllegalArgumentException {

    // Conserva el diagnóstico de la condición inválida.
    public InvalidMemoryConfigurationException(String message) {
        super(message);
    }
}
