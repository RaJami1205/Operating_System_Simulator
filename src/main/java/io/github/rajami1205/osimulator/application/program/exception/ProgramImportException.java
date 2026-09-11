package io.github.rajami1205.osimulator.application.program.exception;

/** Indicates that a program source cannot be imported as semantic instructions. */
public final class ProgramImportException extends Exception {

    public ProgramImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
