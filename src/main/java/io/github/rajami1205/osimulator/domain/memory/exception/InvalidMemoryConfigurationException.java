package io.github.rajami1205.osimulator.domain.memory.exception;

/**
 * Indicates that a simulated memory configuration violates its required invariants.
 */
public class InvalidMemoryConfigurationException extends IllegalArgumentException {

    public InvalidMemoryConfigurationException(String message) {
        super(message);
    }
}
