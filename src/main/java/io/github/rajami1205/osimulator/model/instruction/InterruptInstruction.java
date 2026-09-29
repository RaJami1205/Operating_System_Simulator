package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.InterruptVector;
import java.util.List;
import java.util.Objects;

public record InterruptInstruction(InterruptVector vector) implements Instruction {
    public InterruptInstruction { Objects.requireNonNull(vector, "vector must not be null"); }
    @Override public Opcode opcode() { return Opcode.INT; }
    @Override public List<InstructionOperand> operands() { return List.of(vector); }
    @Override public ExecutionWeight executionWeight() { return vector.executionWeight(); }
}
