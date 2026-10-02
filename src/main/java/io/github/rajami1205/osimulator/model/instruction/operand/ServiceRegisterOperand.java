package io.github.rajami1205.osimulator.model.instruction.operand;

import io.github.rajami1205.osimulator.model.cpu.ServiceRegister;
import java.util.Objects;

/** Referencia tipada a AH/AL para la vista de operandos de MOV, sin convertirlos en registros generales. */
public record ServiceRegisterOperand(ServiceRegister register) implements InstructionOperand {
    /** Exige un registro de servicio no nulo, separado de RegisterName. */
    public ServiceRegisterOperand { Objects.requireNonNull(register, "register must not be null"); }
}
