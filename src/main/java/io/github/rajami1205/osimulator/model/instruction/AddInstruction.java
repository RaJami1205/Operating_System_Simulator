package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import java.util.Objects;

/**
 * Instruction that identifies a register as a future addition source.
 */
public record AddInstruction(RegisterName source) implements Instruction {

    public AddInstruction {
        Objects.requireNonNull(source, "source must not be null");
    }

    @Override
    public Opcode opcode() {
        return Opcode.ADD;
    }
}
