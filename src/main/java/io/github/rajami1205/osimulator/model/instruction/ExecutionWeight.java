package io.github.rajami1205.osimulator.model.instruction;

/** Peso estático; no contiene progreso ni modifica la ejecución actual. */
public record ExecutionWeight(int ticks) {
    public ExecutionWeight {
        if (ticks < 1) {
            throw new IllegalArgumentException("Execution weight must be positive: " + ticks);
        }
    }
}
