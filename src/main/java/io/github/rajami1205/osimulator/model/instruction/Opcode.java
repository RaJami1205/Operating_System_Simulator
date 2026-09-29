package io.github.rajami1205.osimulator.model.instruction;

/**
 * Define las operaciones semánticas admitidas por el conjunto de instrucciones simulado.
 */
public enum Opcode {
    MOV,
    LOAD,
    STORE,
    ADD,
    SUB,
    INC,
    DEC,
    SWAP,
    CMP,
    JMP,
    JE,
    JNE,
    PARAM,
    PUSH,
    POP
}
