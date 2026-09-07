package io.github.rajami1205.osimulator.model.cpu.exception;

/**
 * Indicates that a value is outside the logical range of a simulated data register.
 */
public class InvalidRegisterValueException extends IllegalArgumentException {

    public InvalidRegisterValueException(String message) {
        super(message);
    }
}
