package io.github.rajami1205.osimulator.model.process.exception;

public final class ProcessStackUnderflowException extends IllegalStateException {
    public ProcessStackUnderflowException() {
        super("Process stack is empty");
    }
}
