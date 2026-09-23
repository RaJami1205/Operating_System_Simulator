package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import java.util.Objects;
import java.util.List;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;

/**
 * Representa la instrucción que toma un registro como operando de resta.
 */
public record SubInstruction(RegisterName source) implements Instruction {

    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(3);

    // Valida el registro cuyo valor se restará de AC.
    public SubInstruction {
        Objects.requireNonNull(source, "source must not be null");
    }

    @Override
    // Identifica la operación semántica representada.
    public Opcode opcode() {
        return Opcode.SUB;
    }
    @Override
    public List<InstructionOperand> operands() {
        return List.of(new RegisterOperand(source));
    }

    @Override
    public ExecutionWeight executionWeight() {
        return EXECUTION_WEIGHT;
    }
}
