package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.InterruptVector;
import java.util.List;
import java.util.Objects;

/** Invoca un servicio simulado definido por su vector; su peso depende del servicio. */
public record InterruptInstruction(InterruptVector vector) implements Instruction {
    /** Exige un vector de servicio soportado no nulo. */
    public InterruptInstruction { Objects.requireNonNull(vector, "vector must not be null"); }
    /** Identifica INTERRUPT para dispatch y representación semántica. */
    @Override public Opcode opcode() { return Opcode.INT; }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    @Override public List<InstructionOperand> operands() { return List.of(vector); }
    /** Devuelve el peso en ticks asociado al vector de interrupción. */
    @Override public ExecutionWeight executionWeight() { return vector.executionWeight(); }
}
