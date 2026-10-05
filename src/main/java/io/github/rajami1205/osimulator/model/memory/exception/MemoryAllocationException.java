package io.github.rajami1205.osimulator.model.memory.exception;

/** Tamaño inválido o ausencia de un bloque contiguo suficiente. */
public class MemoryAllocationException extends RuntimeException {
    /** Conserva el diagnóstico de la condición inválida. */
    public MemoryAllocationException(String message) { super(message); }
}
