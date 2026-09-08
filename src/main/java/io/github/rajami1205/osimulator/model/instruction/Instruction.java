package io.github.rajami1205.osimulator.model.instruction;

/**
 * Semantic representation of an instruction supported by the simulator.
 */
public sealed interface Instruction
        permits LoadInstruction, StoreInstruction, AddInstruction, SubInstruction {

    Opcode opcode();
}
