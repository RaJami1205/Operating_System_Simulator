package io.github.rajami1205.osimulator.model.process.exception;

/**
 * Indicates that immutable process metadata violates a required invariant.
 */
public final class InvalidProcessConfigurationException extends IllegalArgumentException {

    public InvalidProcessConfigurationException(String message) {
        super(message);
    }
}
