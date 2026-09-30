package io.github.rajami1205.osimulator.model.instruction.operand;

import io.github.rajami1205.osimulator.model.instruction.ExecutionWeight;
import java.util.Arrays;

/** Supported basic services. Keyboard cost is a project decision, not a recovered PDF value. */
public enum InterruptVector implements InstructionOperand {
    KEYBOARD("09H", 2),
    SCREEN("10H", 2),
    TERMINATE("20H", 2),
    FILESYSTEM("21H", 5);

    private final String canonicalText;
    private final ExecutionWeight executionWeight;

    InterruptVector(String canonicalText, int ticks) {
        this.canonicalText = canonicalText;
        this.executionWeight = new ExecutionWeight(ticks);
    }

    public String canonicalText() { return canonicalText; }
    public ExecutionWeight executionWeight() { return executionWeight; }

    public static InterruptVector parse(String text) {
        return Arrays.stream(values()).filter(vector -> vector.canonicalText.equalsIgnoreCase(text))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported interrupt vector: " + text));
    }
}
