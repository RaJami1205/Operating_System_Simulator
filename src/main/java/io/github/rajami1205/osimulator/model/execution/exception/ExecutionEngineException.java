package io.github.rajami1205.osimulator.model.execution.exception;

/**
 * Señala un fallo durante la ejecución de un paso de instrucción semántica.
 */
public final class ExecutionEngineException extends RuntimeException {

    // Conserva el diagnóstico de la condición inválida.
    public ExecutionEngineException(String message) {
        super(message);
    }

    // Conserva el diagnóstico y la causa original del fallo.
    public ExecutionEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
