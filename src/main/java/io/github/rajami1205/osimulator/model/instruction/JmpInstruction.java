package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.instruction.operand.BranchDisplacement;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import java.util.List;
import java.util.Objects;

/** Salto relativo incondicional a PC + 1 + displacement, limitado al programa del proceso. */
public record JmpInstruction(BranchDisplacement displacement) implements Instruction {
    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(2);

    /** Exige desplazamiento no nulo; el destino se valida contra el programa al ejecutar. */
    public JmpInstruction {
        Objects.requireNonNull(displacement, "displacement must not be null");
    }

    /** Identifica JMP para dispatch y representación semántica. */
    @Override public Opcode opcode() { return Opcode.JMP; }
    /** Devuelve el peso estático en CPU ticks de JMP, sin almacenar progreso. */
    @Override public ExecutionWeight executionWeight() { return EXECUTION_WEIGHT; }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    @Override public List<InstructionOperand> operands() { return List.of(displacement); }
}
