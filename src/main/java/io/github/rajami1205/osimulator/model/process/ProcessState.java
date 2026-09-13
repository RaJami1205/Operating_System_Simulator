package io.github.rajami1205.osimulator.model.process;

/**
 * Define los estados posibles del ciclo de vida de un proceso simulado.
 */
public enum ProcessState {
    NEW,
    READY,
    RUNNING,
    BLOCKED,
    TERMINATED
}
