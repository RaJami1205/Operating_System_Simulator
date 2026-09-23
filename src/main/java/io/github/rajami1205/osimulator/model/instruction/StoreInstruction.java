package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import java.util.Objects;
import java.util.List;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;

/**
 * Representa la instrucción que toma un registro como destino del acumulador.
 */
public record StoreInstruction(RegisterName destination) implements Instruction {

    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(2);

    // Valida el registro que recibirá el valor de AC.
    public StoreInstruction {
        Objects.requireNonNull(destination, "destination must not be null");
    }

    @Override
    // Identifica la operación semántica representada.
    public Opcode opcode() {
        return Opcode.STORE;
    }
    @Override
    public List<InstructionOperand> operands() {
        return List.of(new RegisterOperand(destination));
    }

    @Override
    public ExecutionWeight executionWeight() {
        return EXECUTION_WEIGHT;
    }
}
