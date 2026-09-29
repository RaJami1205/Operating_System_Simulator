package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;
import java.util.List;
import java.util.Objects;

public record PopInstruction(RegisterName destination) implements Instruction {
    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(1);

    public PopInstruction {
        Objects.requireNonNull(destination, "destination must not be null");
    }

    @Override public Opcode opcode() { return Opcode.POP; }
    @Override public ExecutionWeight executionWeight() { return EXECUTION_WEIGHT; }
    @Override public List<InstructionOperand> operands() { return List.of(new RegisterOperand(destination)); }
}
