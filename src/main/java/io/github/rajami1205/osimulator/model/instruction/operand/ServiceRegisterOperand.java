package io.github.rajami1205.osimulator.model.instruction.operand;

import io.github.rajami1205.osimulator.model.cpu.ServiceRegister;
import java.util.Objects;

public record ServiceRegisterOperand(ServiceRegister register) implements InstructionOperand {
    public ServiceRegisterOperand { Objects.requireNonNull(register, "register must not be null"); }
}
