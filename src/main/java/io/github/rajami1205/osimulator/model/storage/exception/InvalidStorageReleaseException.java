package io.github.rajami1205.osimulator.model.storage.exception;

/** La reserva no pertenece al conjunto activo de este allocator. */
public final class InvalidStorageReleaseException extends StorageException {
    public InvalidStorageReleaseException(String message) { super(message); }
}
