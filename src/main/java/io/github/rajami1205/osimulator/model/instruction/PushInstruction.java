package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;
import java.util.List;
import java.util.Objects;

/** Apila el contenido numérico de un registro general en el stack propio del proceso. */
public record PushInstruction(RegisterName source) implements Instruction {

    /** Exige registro fuente no nulo; el engine valida valor y capacidad al ejecutar. */
    public PushInstruction {
        Objects.requireNonNull(source, "source must not be null");
    }

    /** Identifica PUSH para dispatch y representación semántica. */
    @Override public Opcode opcode() { return Opcode.PUSH; }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    @Override public List<InstructionOperand> operands() { return List.of(new RegisterOperand(source)); }
}
