package io.github.rajami1205.osimulator.application.program.exception;

/**
 * Indica que un programa semántico no puede cargarse en la memoria simulada.
 */
public final class ProgramLoadException extends RuntimeException {

    // Conserva el diagnóstico de la condición inválida.
    public ProgramLoadException(String message) {
        super(message);
    }

    // Conserva el diagnóstico y la causa original del fallo.
    public ProgramLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
