package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import java.util.Objects;

/**
 * Instruction that identifies a register as the future source for the accumulator.
 */
public record LoadInstruction(RegisterName source) implements Instruction {

    public LoadInstruction {
        Objects.requireNonNull(source, "source must not be null");
    }

    @Override
    public Opcode opcode() {
        return Opcode.LOAD;
    }
}
