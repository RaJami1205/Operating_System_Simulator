package io.github.rajami1205.osimulator.model.instruction.exception;

/**
 * Indicates that an immediate operand is outside the supported logical data range.
 */
public class InvalidImmediateValueException extends IllegalArgumentException {

    public InvalidImmediateValueException(String message) {
        super(message);
    }
}
