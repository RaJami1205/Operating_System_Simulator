package io.github.rajami1205.osimulator.application.lifecycle;

/**
 * Define los estados lógicos de una sesión del simulador.
 */
public enum SimulatorState {
    CONFIGURING,
    INITIALIZED,
    PROGRAM_LOADED,
    RUNNING,
    PAUSED,
    FINISHED,
    ERROR
}
