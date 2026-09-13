package io.github.rajami1205.osimulator.application.lifecycle.exception;

/**
 * Indica que una operación del ciclo de vida no es válida en el estado actual.
 */
public final class InvalidSimulatorTransitionException extends IllegalStateException {

    // Conserva el diagnóstico de la condición inválida.
    public InvalidSimulatorTransitionException(String message) {
        super(message);
    }
}
