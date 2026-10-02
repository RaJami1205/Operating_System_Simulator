package io.github.rajami1205.osimulator.model.instruction.operand;

/** Desplazamiento relativo del PC lógico, independiente del rango signed 16-bit de datos. */
public record BranchDisplacement(int value) implements InstructionOperand {
}
