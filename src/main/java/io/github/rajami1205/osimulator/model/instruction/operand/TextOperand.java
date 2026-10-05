package io.github.rajami1205.osimulator.model.instruction.operand;

import java.util.Objects;

/** Texto semántico sin comillas de origen; las comillas pertenecen al parser y al formatting. */
public record TextOperand(String value) implements InstructionOperand {
    /** Exige texto no nulo y conserva su contenido semántico sin comillas de origen. */
    public TextOperand { Objects.requireNonNull(value, "text must not be null"); }
}
