package io.github.rajami1205.osimulator.model.instruction.operand;

import io.github.rajami1205.osimulator.model.instruction.ExecutionWeight;
import java.util.Arrays;

/**
 * Vectores soportados y sus pesos. El costo de INT09 es una decisión del proyecto, no un valor recuperado
 * del enunciado.
 */
public enum InterruptVector implements InstructionOperand {
    KEYBOARD("09H", 2),
    SCREEN("10H", 2),
    TERMINATE("20H", 2),
    FILESYSTEM("21H", 5);

    private final String canonicalText;
    private final ExecutionWeight executionWeight;

    /** Asocia el texto canónico del vector con un ExecutionWeight positivo. */
    InterruptVector(String canonicalText, int ticks) {
        this.canonicalText = canonicalText;
        this.executionWeight = new ExecutionWeight(ticks);
    }

    /** Devuelve la notación hexadecimal canónica que utiliza el formatting ASM. */
    public String canonicalText() { return canonicalText; }
    /** Devuelve el peso estático del servicio en CPU ticks. */
    public ExecutionWeight executionWeight() { return executionWeight; }

    /** Resuelve un vector soportado sin distinguir mayúsculas; rechaza cualquier código ajeno al enum. */
    public static InterruptVector parse(String text) {
        return Arrays.stream(values()).filter(vector -> vector.canonicalText.equalsIgnoreCase(text))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported interrupt vector: " + text));
    }
}
