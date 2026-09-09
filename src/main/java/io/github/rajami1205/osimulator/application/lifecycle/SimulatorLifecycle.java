package io.github.rajami1205.osimulator.application.lifecycle;

import io.github.rajami1205.osimulator.application.lifecycle.exception.InvalidSimulatorTransitionException;

/**
 * Controls the logical lifecycle state of a simulator session.
 */
public final class SimulatorLifecycle {

    private SimulatorState state;

    public SimulatorLifecycle() {
        state = SimulatorState.CONFIGURING;
    }

    public SimulatorState state() {
        return state;
    }

    public void initialize() {
        transition(
                "initialize",
                SimulatorState.CONFIGURING,
                SimulatorState.INITIALIZED
        );
    }

    public void markProgramLoaded() {
        transition(
                "markProgramLoaded",
                SimulatorState.INITIALIZED,
                SimulatorState.PROGRAM_LOADED
        );
    }

    public void startExecution() {
        transition(
                "startExecution",
                SimulatorState.PROGRAM_LOADED,
                SimulatorState.RUNNING
        );
    }

    public void pauseExecution() {
        transition(
                "pauseExecution",
                SimulatorState.RUNNING,
                SimulatorState.PAUSED
        );
    }

    public void resumeExecution() {
        transition(
                "resumeExecution",
                SimulatorState.PAUSED,
                SimulatorState.RUNNING
        );
    }

    public void finishExecution() {
        transition(
                "finishExecution",
                SimulatorState.RUNNING,
                SimulatorState.FINISHED
        );
    }

    public void markError() {
        state = SimulatorState.ERROR;
    }

    public void reset() {
        state = SimulatorState.CONFIGURING;
    }

    private void transition(
            String operation,
            SimulatorState requiredState,
            SimulatorState targetState
    ) {
        if (state != requiredState) {
            throw new InvalidSimulatorTransitionException(
                    "Cannot " + operation + " while simulator state is " + state
            );
        }

        state = targetState;
    }
}
