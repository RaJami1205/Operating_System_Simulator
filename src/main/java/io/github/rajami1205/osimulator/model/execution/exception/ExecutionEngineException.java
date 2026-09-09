package io.github.rajami1205.osimulator.model.execution.exception;

/**
 * Signals a failure while executing a semantic instruction step.
 */
public final class ExecutionEngineException extends RuntimeException {

    public ExecutionEngineException(String message) {
        super(message);
    }

    public ExecutionEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
