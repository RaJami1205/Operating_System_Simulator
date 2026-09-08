package io.github.rajami1205.osimulator.model.process.exception;

/**
 * Indicates that a saved process Program Counter is outside its program range.
 */
public final class InvalidProcessProgramCounterException extends IllegalArgumentException {

    public InvalidProcessProgramCounterException(String message) {
        super(message);
    }
}
