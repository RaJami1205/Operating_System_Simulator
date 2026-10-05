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
                SubInstruction,
                IncInstruction,
                DecInstruction,
                SwapInstruction, CmpInstruction, JmpInstruction, JeInstruction, JneInstruction,
                ParamInstruction, PushInstruction, PopInstruction, InterruptInstruction {

    /** Identifica el tipo semántico de instrucción para dispatch y formatting. */
    Opcode opcode();

    /** Vista derivada, ordenada e inmutable de los operandos semánticos. */
    List<InstructionOperand> operands();

    /**
     * Expone el peso oficial en CPU ticks; ExecutionEngine conserva el progreso y aplica la semántica al
     * completarlo.
     */
    ExecutionWeight executionWeight();
}
