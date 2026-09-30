package io.github.rajami1205.osimulator.model.instruction.operand;

import java.util.Objects;

/** Semantic text without source quotes. */
public record TextOperand(String value) implements InstructionOperand {
    public TextOperand { Objects.requireNonNull(value, "text must not be null"); }
}
