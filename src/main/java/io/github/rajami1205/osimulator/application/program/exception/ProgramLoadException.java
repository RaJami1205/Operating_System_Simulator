package io.github.rajami1205.osimulator.application.program.exception;

/**
 * Indicates that a semantic program cannot be loaded into simulated Memory.
 */
public final class ProgramLoadException extends RuntimeException {

    public ProgramLoadException(String message) {
        super(message);
    }

    public ProgramLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
