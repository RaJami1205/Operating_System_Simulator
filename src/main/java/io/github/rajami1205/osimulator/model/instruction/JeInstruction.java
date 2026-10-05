package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.instruction.operand.BranchDisplacement;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import java.util.List;
import java.util.Objects;

/** Salto relativo si equal es true; el engine calcula PC + 1 + displacement y valida el destino tomado. */
public record JeInstruction(BranchDisplacement displacement) implements Instruction {
    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(2);

    /** Exige desplazamiento no nulo; el engine valida el destino si toma la rama. */
    public JeInstruction {
        Objects.requireNonNull(displacement, "displacement must not be null");
    }

    /** Identifica JE para dispatch y representación semántica. */
    @Override public Opcode opcode() { return Opcode.JE; }
    /** Devuelve el peso estático en CPU ticks de JE, sin almacenar progreso. */
    @Override public ExecutionWeight executionWeight() { return EXECUTION_WEIGHT; }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    @Override public List<InstructionOperand> operands() { return List.of(displacement); }
}
