package io.github.rajami1205.osimulator.application.lifecycle;

import io.github.rajami1205.osimulator.application.lifecycle.exception.InvalidSimulatorTransitionException;

/**
 * Controla el estado lógico del ciclo de vida de una sesión del simulador.
 */
public final class SimulatorLifecycle {

    private SimulatorState state;

    // Crea una sesión lógica en estado CONFIGURING.
    public SimulatorLifecycle() {
        state = SimulatorState.CONFIGURING;
    }

    // Expone el estado lógico actual de la sesión.
    public SimulatorState state() {
        return state;
    }

    // Transiciona de configuración a sesión inicializada.
    public void initialize() {
        transition(
                "initialize",
                SimulatorState.CONFIGURING,
                SimulatorState.INITIALIZED
        );
    }

    // Marca la disponibilidad del programa tras inicializar la sesión.
    public void markProgramLoaded() {
        transition(
                "markProgramLoaded",
                SimulatorState.INITIALIZED,
                SimulatorState.PROGRAM_LOADED
        );
    }

    // Habilita la ejecución de un programa cargado.
    public void startExecution() {
        transition(
                "startExecution",
                SimulatorState.PROGRAM_LOADED,
                SimulatorState.RUNNING
        );
    }

    // Suspende la ejecución de una sesión activa.
    public void pauseExecution() {
        transition(
                "pauseExecution",
                SimulatorState.RUNNING,
                SimulatorState.PAUSED
        );
    }

    // Habilita nuevamente la ejecución de una sesión pausada.
    public void resumeExecution() {
        transition(
                "resumeExecution",
                SimulatorState.PAUSED,
                SimulatorState.RUNNING
        );
    }

    // Marca como finalizada una sesión en ejecución.
    public void finishExecution() {
        transition(
                "finishExecution",
                SimulatorState.RUNNING,
                SimulatorState.FINISHED
        );
    }

    // Marca la sesión con un error de ejecución.
    public void markError() {
        state = SimulatorState.ERROR;
    }

    // Devuelve el lifecycle a CONFIGURING desde cualquier estado.
    public void reset() {
        state = SimulatorState.CONFIGURING;
    }

    // Valida el estado de origen antes de aplicar una transición.
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
