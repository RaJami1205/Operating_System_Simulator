package io.github.rajami1205.osimulator.model.program;

import io.github.rajami1205.osimulator.model.instruction.Instruction;
import java.util.List;
import java.util.Objects;

/** Programa semántico inmutable, independiente de su origen y ubicación física. */
public record ProgramImage(String logicalName, List<Instruction> instructions) {
    public ProgramImage {
        Objects.requireNonNull(logicalName, "logicalName must not be null");
        if (logicalName.isBlank()) throw new IllegalArgumentException("Program name must not be blank");
        instructions = List.copyOf(Objects.requireNonNull(instructions, "instructions must not be null"));
        if (instructions.isEmpty()) throw new IllegalArgumentException("Program must not be empty");
    }
}
