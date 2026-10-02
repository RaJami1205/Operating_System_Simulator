package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.instruction.operand.ImmediateOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import java.util.List;

/**
 * Conserva de uno a tres valores en orden textual; el engine los inserta en orden inverso para
 * recuperarlos con POP en ese orden.
 */
public record ParamInstruction(List<ImmediateOperand> values) implements Instruction {
    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(3);
    public static final int MAX_VALUES = 3;

    /** Copia de uno a tres valores no nulos sin cambiar su orden textual. */
    public ParamInstruction {
        values = List.copyOf(values);
        if (values.isEmpty() || values.size() > MAX_VALUES) {
            throw new IllegalArgumentException("PARAM requires one to three values");
        }
    }

    /** Identifica PARAM para dispatch y representación semántica. */
    @Override public Opcode opcode() { return Opcode.PARAM; }
    /** Devuelve el peso estático en CPU ticks de PARAM, sin almacenar progreso. */
    @Override public ExecutionWeight executionWeight() { return EXECUTION_WEIGHT; }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    @Override public List<InstructionOperand> operands() { return List.copyOf(values); }
}
