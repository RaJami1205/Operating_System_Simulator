package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import java.util.List;

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

    /** Vista derivada, ordenada e inmutable de los operandos semánticos. */
    List<InstructionOperand> operands();

    /** Peso oficial del tipo de instrucción; metadata sin efecto runtime en F05. */
    ExecutionWeight executionWeight();
}
