package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.instruction.operand.BranchDisplacement;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import java.util.List;
import java.util.Objects;

public record JeInstruction(BranchDisplacement displacement) implements Instruction {
    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(2);

    public JeInstruction {
        Objects.requireNonNull(displacement, "displacement must not be null");
    }

    @Override public Opcode opcode() { return Opcode.JE; }
    @Override public ExecutionWeight executionWeight() { return EXECUTION_WEIGHT; }
    @Override public List<InstructionOperand> operands() { return List.of(displacement); }
}
