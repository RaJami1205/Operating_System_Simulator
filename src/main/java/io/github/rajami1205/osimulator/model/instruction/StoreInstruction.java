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

    /** Exige registro destino no nulo para recibir AC. */
    public StoreInstruction {
        Objects.requireNonNull(destination, "destination must not be null");
    }

    /** Identifica STORE para dispatch y representación semántica. */
    @Override
    public Opcode opcode() {
        return Opcode.STORE;
    }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    @Override
    public List<InstructionOperand> operands() {
        return List.of(new RegisterOperand(destination));
    }

    /** Devuelve el peso estático en CPU ticks de STORE, sin almacenar progreso. */
    @Override
    public ExecutionWeight executionWeight() {
        return EXECUTION_WEIGHT;
    }
}
