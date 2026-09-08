package io.github.rajami1205.osimulator.model.process;

/**
 * Possible lifecycle states of a simulated process.
 */
public enum ProcessState {
    NEW,
    READY,
    RUNNING,
    BLOCKED,
    TERMINATED
}
