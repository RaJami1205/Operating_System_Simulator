package io.github.rajami1205.osimulator.model.storage.exception;

/** Solicitud inválida o falta de capacidad contigua. */
public final class StorageAllocationException extends StorageException {
    public StorageAllocationException(String message) { super(message); }
}
