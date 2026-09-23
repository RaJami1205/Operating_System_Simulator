package io.github.rajami1205.osimulator.model.process.exception;

public final class ProcessStackOverflowException extends IllegalStateException {
    public ProcessStackOverflowException() {
        super("Process stack is full");
    }
}
