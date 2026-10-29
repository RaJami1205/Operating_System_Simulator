package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Ausencia de target significa AC; un target explícito siempre es un registro general. */
public record DecInstruction(Optional<RegisterName> target) implements Instruction {

    /** Exige un Optional no nulo; vacío representa AC y presente identifica un registro general. */
    public DecInstruction { Objects.requireNonNull(target, "target must not be null"); }
    /** Selecciona AC como destino implícito. */
    public DecInstruction() { this(Optional.empty()); }
    /** Selecciona un registro general no nulo como destino explícito. */
    public DecInstruction(RegisterName target) { this(Optional.of(target)); }
    /** Identifica DEC para dispatch y representación semántica. */
    public Opcode opcode() { return Opcode.DEC; }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    public List<InstructionOperand> operands() {
        return target.<List<InstructionOperand>>map(register -> List.of(new RegisterOperand(register)))
                .orElseGet(List::of);
    }
}
