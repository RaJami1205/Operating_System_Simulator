package io.github.rajami1205.osimulator.application.lifecycle.exception;

/**
 * Indicates that a lifecycle operation is invalid for the current simulator state.
 */
public final class InvalidSimulatorTransitionException extends IllegalStateException {

    public InvalidSimulatorTransitionException(String message) {
        super(message);
    }
}
