package io.github.rajami1205.osimulator.model.filesystem;

/**
 * Fallo controlado de una operación del filesystem simulado, traducido por ExecutionEngine sin códigos de
 * estado DOS.
 */
public final class FileSystemException extends RuntimeException {
    /** Conserva el diagnóstico de la condición inválida. */
    public FileSystemException(String message) { super(message); }
    /** Conserva el diagnóstico de la condición inválida y su causa original. */
    public FileSystemException(String message, Throwable cause) { super(message, cause); }
}
