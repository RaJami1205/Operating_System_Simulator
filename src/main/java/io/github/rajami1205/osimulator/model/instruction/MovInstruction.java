package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.ImmediateOperand;

import java.util.Objects;
import java.util.List;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;

/**
 * Asigna un inmediato o el valor de un registro a un registro destino.
 */
public record MovInstruction(RegisterName destination, InstructionOperand source) implements Instruction {

    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(1);

    // Los operandos tipados ya validan su contenido.
    public MovInstruction {
        Objects.requireNonNull(destination, "destination must not be null");

        Objects.requireNonNull(source, "source must not be null");
    }

    public MovInstruction(RegisterName destination, int immediate) {
        this(Objects.requireNonNull(destination, "destination must not be null"), new ImmediateOperand(immediate));
    }

    public MovInstruction(RegisterName destination, RegisterName source) {
        this(destination, new RegisterOperand(source));
    }

    @Override
    // Identifica la operación semántica representada.
    public Opcode opcode() {
        return Opcode.MOV;
    }
    @Override
    public List<InstructionOperand> operands() {
        return List.of(new RegisterOperand(destination), source);
    }

    @Override
    public ExecutionWeight executionWeight() {
        return EXECUTION_WEIGHT;
    }
}
