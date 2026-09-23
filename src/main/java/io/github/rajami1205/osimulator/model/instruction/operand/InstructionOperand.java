package io.github.rajami1205.osimulator.model.instruction.operand;

/** Operando semántico inmutable de una instrucción. */
public sealed interface InstructionOperand permits RegisterOperand, ImmediateOperand {
}
