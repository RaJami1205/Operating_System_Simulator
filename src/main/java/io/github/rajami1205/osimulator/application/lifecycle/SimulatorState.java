package io.github.rajami1205.osimulator.application.lifecycle;

/**
 * Logical states of a simulator session.
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
