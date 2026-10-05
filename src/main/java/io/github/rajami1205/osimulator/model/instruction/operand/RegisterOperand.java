package io.github.rajami1205.osimulator.model.instruction.operand;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import java.util.Objects;

/** Referencia tipada exclusivamente a AX/BX/CX/DX, sin incluir los registros de servicio. */
public record RegisterOperand(RegisterName register) implements InstructionOperand {
    /** Exige un registro general no nulo como operando. */
    public RegisterOperand {
        Objects.requireNonNull(register, "register must not be null");
    }
}
