package io.github.rajami1205.osimulator.model.process.exception;

/**
 * Indica que los metadatos inmutables de un proceso incumplen una invariante.
 */
public final class InvalidProcessConfigurationException extends IllegalArgumentException {

    // Conserva el diagnóstico de la condición inválida.
    public InvalidProcessConfigurationException(String message) {
        super(message);
    }
}
