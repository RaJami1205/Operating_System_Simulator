package io.github.rajami1205.osimulator.model.instruction;

/**
 * Define la representación semántica de una instrucción admitida por el simulador.
 */
public sealed interface Instruction
        permits MovInstruction,
                LoadInstruction,
                StoreInstruction,
                AddInstruction,
                SubInstruction {

    // Identifica la operación semántica representada.
    Opcode opcode();
}
