package io.github.rajami1205.osimulator.model.instruction.operand;

import io.github.rajami1205.osimulator.model.cpu.CpuValueRange;
import io.github.rajami1205.osimulator.model.instruction.exception.InvalidImmediateValueException;

public record ImmediateOperand(int value) implements InstructionOperand {
    public ImmediateOperand {
        validate(value);
    }

    /** Comparte la validación con instrucciones que conservan componentes primitivos. */
    public static void validate(int value) {
        if (!CpuValueRange.contains(value)) {
            throw new InvalidImmediateValueException("Immediate value must be between "
                    + CpuValueRange.MIN_VALUE + " and " + CpuValueRange.MAX_VALUE + ": " + value);
        }
    }
}
