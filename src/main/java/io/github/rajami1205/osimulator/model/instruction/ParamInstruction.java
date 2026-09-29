package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.instruction.operand.ImmediateOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import java.util.List;

/** Parameters retained in textual order; execution reverses their stack insertion. */
public record ParamInstruction(List<ImmediateOperand> values) implements Instruction {
    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(3);
    public static final int MAX_VALUES = 3;

    public ParamInstruction {
        values = List.copyOf(values);
        if (values.isEmpty() || values.size() > MAX_VALUES) {
            throw new IllegalArgumentException("PARAM requires one to three values");
        }
    }

    @Override public Opcode opcode() { return Opcode.PARAM; }
    @Override public ExecutionWeight executionWeight() { return EXECUTION_WEIGHT; }
    @Override public List<InstructionOperand> operands() { return List.copyOf(values); }
}
