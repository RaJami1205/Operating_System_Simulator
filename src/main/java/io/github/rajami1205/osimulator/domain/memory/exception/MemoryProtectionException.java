package io.github.rajami1205.osimulator.domain.memory.exception;

/**
 * Indicates that a memory operation targets a valid but protected region.
 */
public class MemoryProtectionException extends RuntimeException {

    public MemoryProtectionException(String message) {
        super(message);
    }
}
