package io.github.rajami1205.osimulator.model.instruction;

/**
 * Cantidad positiva de CPU ticks requerida para completar una instrucción; metadata estática, no progreso
 * ni tiempo real.
 */
public record ExecutionWeight(int ticks) {
    /** Rechaza pesos no positivos; los ticks son metadata del tipo de instrucción. */
    public ExecutionWeight {
        if (ticks < 1) {
            throw new IllegalArgumentException("Execution weight must be positive: " + ticks);
        }
    }
}
