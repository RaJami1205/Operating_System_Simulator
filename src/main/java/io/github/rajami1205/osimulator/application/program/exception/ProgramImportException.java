package io.github.rajami1205.osimulator.application.program.exception;

/** Indica que el programa de origen no puede importarse como instrucciones semánticas. */
public final class ProgramImportException extends Exception {

    // Conserva el diagnóstico y la causa original del fallo.
    public ProgramImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
