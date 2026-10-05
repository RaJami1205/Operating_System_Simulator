package io.github.rajami1205.osimulator.model.process;

/**
 * Estado del proceso, separado del lifecycle: READY espera CPU, RUNNING lo posee y BLOCKED espera input;
 * las variantes suspended no poseen USER físico. TERMINATED precede al cleanup.
 */
public enum ProcessState {
    NEW,
    READY,
    RUNNING,
    BLOCKED,
    READY_SUSPENDED,
    BLOCKED_SUSPENDED,
    TERMINATED
}
