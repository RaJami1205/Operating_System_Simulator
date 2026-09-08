package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import java.util.Objects;

/**
 * Instruction that identifies a register as the future destination of the accumulator.
 */
public record StoreInstruction(RegisterName destination) implements Instruction {

    public StoreInstruction {
        Objects.requireNonNull(destination, "destination must not be null");
    }

    @Override
    public Opcode opcode() {
        return Opcode.STORE;
    }
}
