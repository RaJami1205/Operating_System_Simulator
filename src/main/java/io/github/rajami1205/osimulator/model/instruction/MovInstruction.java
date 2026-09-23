package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.ImmediateOperand;

import java.util.Objects;
import java.util.List;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;

/**
 * Representa la instrucción que asigna un inmediato a un registro destino al ejecutarse.
 */
public record MovInstruction(RegisterName destination, int immediate) implements Instruction {

    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(1);

    // Valida el registro destino y el rango lógico del inmediato.
    public MovInstruction {
        Objects.requireNonNull(destination, "destination must not be null");

        ImmediateOperand.validate(immediate);
    }

    @Override
    // Identifica la operación semántica representada.
    public Opcode opcode() {
        return Opcode.MOV;
    }
    @Override
    public List<InstructionOperand> operands() {
        return List.of(new RegisterOperand(destination), new ImmediateOperand(immediate));
    }

    @Override
    public ExecutionWeight executionWeight() {
        return EXECUTION_WEIGHT;
    }
}
