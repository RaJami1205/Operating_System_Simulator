package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.exception.InvalidImmediateValueException;
import java.util.Objects;

/**
 * Instruction that assigns an immediate value to a destination register when executed later.
 */
public record MovInstruction(RegisterName destination, int immediate) implements Instruction {

    private static final int MIN_IMMEDIATE_VALUE = -127;
    private static final int MAX_IMMEDIATE_VALUE = 127;

    public MovInstruction {
        Objects.requireNonNull(destination, "destination must not be null");

        if (immediate < MIN_IMMEDIATE_VALUE || immediate > MAX_IMMEDIATE_VALUE) {
            throw new InvalidImmediateValueException(
                    "Immediate value must be between "
                            + MIN_IMMEDIATE_VALUE
                            + " and "
                            + MAX_IMMEDIATE_VALUE
                            + ": "
                            + immediate
            );
        }
    }

    @Override
    public Opcode opcode() {
        return Opcode.MOV;
    }
}
