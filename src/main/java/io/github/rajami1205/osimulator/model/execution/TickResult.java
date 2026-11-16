package io.github.rajami1205.osimulator.model.execution;

/**
 * Distingue instrucción completa, programa terminado y fetch ausente sin efectos.
 * PAGE_FAULT no es un tick exitoso ni representa el lifecycle global.
 */
public enum TickResult {
    INSTRUCTION_COMPLETED,
    PROGRAM_FINISHED,
    PAGE_FAULT
}
