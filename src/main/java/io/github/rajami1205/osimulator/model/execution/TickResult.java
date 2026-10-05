package io.github.rajami1205.osimulator.model.execution;

/**
 * Distingue progreso intermedio, instrucción completa, programa terminado y bloqueo por input; no
 * representa el lifecycle global.
 */
public enum TickResult {
    IN_PROGRESS,
    INSTRUCTION_COMPLETED,
    PROGRAM_FINISHED,
    WAITING_FOR_INPUT
}
