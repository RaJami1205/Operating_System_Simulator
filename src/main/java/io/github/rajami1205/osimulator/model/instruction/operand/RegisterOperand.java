package io.github.rajami1205.osimulator.model.instruction.operand;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import java.util.Objects;

public record RegisterOperand(RegisterName register) implements InstructionOperand {
    public RegisterOperand {
        Objects.requireNonNull(register, "register must not be null");
    }
}
