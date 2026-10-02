package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;
import java.util.List;
import java.util.Objects;

/** Apila el contenido numérico de un registro general en el stack propio del proceso. */
public record PushInstruction(RegisterName source) implements Instruction {
    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(1);

    /** Exige registro fuente no nulo; el engine valida valor y capacidad al ejecutar. */
    public PushInstruction {
        Objects.requireNonNull(source, "source must not be null");
    }

    /** Identifica PUSH para dispatch y representación semántica. */
    @Override public Opcode opcode() { return Opcode.PUSH; }
    /** Devuelve el peso estático en CPU ticks de PUSH, sin almacenar progreso. */
    @Override public ExecutionWeight executionWeight() { return EXECUTION_WEIGHT; }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    @Override public List<InstructionOperand> operands() { return List.of(new RegisterOperand(source)); }
}
