package io.github.rajami1205.osimulator.model.cpu.exception;

/**
 * Indicates that a value cannot represent the simulated Program Counter.
 */
public class InvalidProgramCounterException extends IllegalArgumentException {

    public InvalidProgramCounterException(String message) {
        super(message);
    }
}
