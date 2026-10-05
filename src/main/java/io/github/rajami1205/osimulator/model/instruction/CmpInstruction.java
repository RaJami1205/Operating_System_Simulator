package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;
import java.util.List;
import java.util.Objects;

/** Compara dos registros numéricos y actualiza equal al completar su peso, sin modificar operandos. */
public record CmpInstruction(RegisterName left, RegisterName right) implements Instruction {
    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(2);

    /** Exige dos registros generales no nulos para la comparación. */
    public CmpInstruction {
        Objects.requireNonNull(left, "left must not be null");
        Objects.requireNonNull(right, "right must not be null");
    }

    /** Identifica CMP para dispatch y representación semántica. */
    @Override public Opcode opcode() { return Opcode.CMP; }
    /** Devuelve el peso estático en CPU ticks de CMP, sin almacenar progreso. */
    @Override public ExecutionWeight executionWeight() { return EXECUTION_WEIGHT; }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    @Override public List<InstructionOperand> operands() { return List.of(new RegisterOperand(left), new RegisterOperand(right)); }
}
