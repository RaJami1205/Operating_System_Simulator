package io.github.rajami1205.osimulator.model.instruction.operand;

import java.util.Arrays;

/** Vectores de servicios simulados soportados. */
public enum InterruptVector implements InstructionOperand {
    KEYBOARD("09H"),
    SCREEN("10H"),
    TERMINATE("20H"),
    FILESYSTEM("21H");

    private final String canonicalText;

    /** Asocia el texto canónico del vector. */
    InterruptVector(String canonicalText) {
        this.canonicalText = canonicalText;
    }

    /** Devuelve la notación hexadecimal canónica que utiliza el formatting ASM. */
    public String canonicalText() { return canonicalText; }

    /** Resuelve un vector soportado sin distinguir mayúsculas; rechaza cualquier código ajeno al enum. */
    public static InterruptVector parse(String text) {
        return Arrays.stream(values()).filter(vector -> vector.canonicalText.equalsIgnoreCase(text))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported interrupt vector: " + text));
    }
}
