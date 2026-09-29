package io.github.rajami1205.osimulator.model.instruction.operand;

/** Relative logical-PC offset, independent from the CPU data range. */
public record BranchDisplacement(int value) implements InstructionOperand {
}
