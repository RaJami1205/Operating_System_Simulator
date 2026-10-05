package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import java.util.Objects;
import java.util.List;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;

/**
 * Representa la instrucción que toma un registro como operando de suma.
 */
public record AddInstruction(RegisterName source) implements Instruction {

    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(3);

    /** Exige registro fuente no nulo para sumar al AC. */
    public AddInstruction {
        Objects.requireNonNull(source, "source must not be null");
    }

    /** Identifica ADD para dispatch y representación semántica. */
    @Override
    public Opcode opcode() {
        return Opcode.ADD;
    }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    @Override
    public List<InstructionOperand> operands() {
        return List.of(new RegisterOperand(source));
    }

    /** Devuelve el peso estático en CPU ticks de ADD, sin almacenar progreso. */
    @Override
    public ExecutionWeight executionWeight() {
        return EXECUTION_WEIGHT;
    }
}
