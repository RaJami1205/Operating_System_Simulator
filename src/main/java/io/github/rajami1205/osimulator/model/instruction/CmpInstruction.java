package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;
import java.util.List;
import java.util.Objects;

public record CmpInstruction(RegisterName left, RegisterName right) implements Instruction {
    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(2);

    public CmpInstruction {
        Objects.requireNonNull(left, "left must not be null");
        Objects.requireNonNull(right, "right must not be null");
    }

    @Override public Opcode opcode() { return Opcode.CMP; }
    @Override public ExecutionWeight executionWeight() { return EXECUTION_WEIGHT; }
    @Override public List<InstructionOperand> operands() { return List.of(new RegisterOperand(left), new RegisterOperand(right)); }
}
