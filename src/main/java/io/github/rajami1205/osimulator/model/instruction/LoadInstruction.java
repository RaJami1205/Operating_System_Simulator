package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import java.util.Objects;
import java.util.List;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;

/**
 * Representa la instrucción que toma un registro como origen para el acumulador.
 */
public record LoadInstruction(RegisterName source) implements Instruction {

    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(2);

    // Valida el registro que proporcionará el valor para AC.
    public LoadInstruction {
        Objects.requireNonNull(source, "source must not be null");
    }

    @Override
    // Identifica la operación semántica representada.
    public Opcode opcode() {
        return Opcode.LOAD;
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
