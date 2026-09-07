package io.github.rajami1205.osimulator.model.memory.exception;

/**
 * Indicates that an address is outside the configured simulated memory space.
 */
public class InvalidMemoryAddressException extends IndexOutOfBoundsException {

    public InvalidMemoryAddressException(String message) {
        super(message);
    }
}
