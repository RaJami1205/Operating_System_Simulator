package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;
import java.util.List;
import java.util.Objects;

/** Extrae el tope numérico del stack del proceso a un registro general; un stack vacío produce underflow. */
public record PopInstruction(RegisterName destination) implements Instruction {

    /** Exige registro destino no nulo; el engine comprueba underflow al ejecutar. */
    public PopInstruction {
        Objects.requireNonNull(destination, "destination must not be null");
    }

    /** Identifica POP para dispatch y representación semántica. */
    @Override public Opcode opcode() { return Opcode.POP; }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    @Override public List<InstructionOperand> operands() { return List.of(new RegisterOperand(destination)); }
}
