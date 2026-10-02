package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;
import java.util.List;
import java.util.Objects;

/** Intercambia dos registros; operandos iguales representan un no-op válido. */
public record SwapInstruction(RegisterName left, RegisterName right) implements Instruction {
    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(1);

    /** Exige dos registros generales no nulos; permite que ambos sean el mismo. */
    public SwapInstruction {
        Objects.requireNonNull(left, "left must not be null");
        Objects.requireNonNull(right, "right must not be null");
    }
    /** Identifica SWAP para dispatch y representación semántica. */
    public Opcode opcode() { return Opcode.SWAP; }
    /** Devuelve el peso estático en CPU ticks de SWAP, sin almacenar progreso. */
    public ExecutionWeight executionWeight() { return EXECUTION_WEIGHT; }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    public List<InstructionOperand> operands() {
        return List.of(new RegisterOperand(left), new RegisterOperand(right));
    }
}
