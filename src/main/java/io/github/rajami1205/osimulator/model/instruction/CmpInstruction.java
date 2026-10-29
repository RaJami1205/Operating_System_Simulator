package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;
import java.util.List;
import java.util.Objects;

/** Compara dos registros numéricos y actualiza equal en un tick, sin modificar operandos. */
public record CmpInstruction(RegisterName left, RegisterName right) implements Instruction {

    /** Exige dos registros generales no nulos para la comparación. */
    public CmpInstruction {
        Objects.requireNonNull(left, "left must not be null");
        Objects.requireNonNull(right, "right must not be null");
    }

    /** Identifica CMP para dispatch y representación semántica. */
    @Override public Opcode opcode() { return Opcode.CMP; }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    @Override public List<InstructionOperand> operands() { return List.of(new RegisterOperand(left), new RegisterOperand(right)); }
}
