package io.github.rajami1205.osimulator.model.memory.exception;

/**
 * Indicates that a memory operation targets a valid but protected region.
 */
public class MemoryProtectionException extends RuntimeException {

    public MemoryProtectionException(String message) {
        super(message);
    }
}
