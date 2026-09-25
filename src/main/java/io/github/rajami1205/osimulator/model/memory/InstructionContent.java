package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.instruction.Instruction;
import java.util.Objects;

public record InstructionContent(Instruction instruction) implements MemoryContent {
    public InstructionContent {
        Objects.requireNonNull(instruction, "instruction must not be null");
    }
}
