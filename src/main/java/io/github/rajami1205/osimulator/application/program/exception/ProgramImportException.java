package io.github.rajami1205.osimulator.application.program.exception;

/** Indica que el programa de origen no puede importarse como instrucciones semánticas. */
public final class ProgramImportException extends Exception {

    /** Conserva el diagnóstico de la condición inválida y su causa original. */
    public ProgramImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
