package io.github.rajami1205.osimulator.model.memory.exception;

/** La reserva no pertenece al conjunto activo de este allocator. */
public class InvalidMemoryReleaseException extends RuntimeException {
    /** Conserva el diagnóstico de la condición inválida. */
    public InvalidMemoryReleaseException(String message) { super(message); }
}
