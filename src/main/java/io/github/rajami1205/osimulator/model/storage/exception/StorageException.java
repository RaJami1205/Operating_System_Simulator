package io.github.rajami1205.osimulator.model.storage.exception;

/** Error controlado de una operación de almacenamiento simulado. */
public class StorageException extends RuntimeException {
    /** Conserva el diagnóstico de la condición inválida. */
    public StorageException(String message) { super(message); }
}
