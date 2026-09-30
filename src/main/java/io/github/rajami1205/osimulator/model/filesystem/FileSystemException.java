package io.github.rajami1205.osimulator.model.filesystem;

/** Controlled simulated syscall failure, with no DOS status-code ABI. */
public final class FileSystemException extends RuntimeException {
    public FileSystemException(String message) { super(message); }
    public FileSystemException(String message, Throwable cause) { super(message, cause); }
}
