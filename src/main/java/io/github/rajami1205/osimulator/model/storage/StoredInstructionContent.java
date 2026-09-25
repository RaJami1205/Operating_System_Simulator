package io.github.rajami1205.osimulator.model.storage;

import io.github.rajami1205.osimulator.model.instruction.Instruction;
import java.util.Objects;

public record StoredInstructionContent(Instruction instruction) implements StorageContent {
    public StoredInstructionContent {
        Objects.requireNonNull(instruction, "instruction must not be null");
    }
}
