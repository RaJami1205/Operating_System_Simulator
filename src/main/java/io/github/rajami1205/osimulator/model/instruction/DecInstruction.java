package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Ausencia de target significa AC; un target explícito siempre es un registro general. */
public record DecInstruction(Optional<RegisterName> target) implements Instruction {
    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(1);

    public DecInstruction { Objects.requireNonNull(target, "target must not be null"); }
    public DecInstruction() { this(Optional.empty()); }
    public DecInstruction(RegisterName target) { this(Optional.of(target)); }
    public Opcode opcode() { return Opcode.DEC; }
    public ExecutionWeight executionWeight() { return EXECUTION_WEIGHT; }
    public List<InstructionOperand> operands() {
        return target.<List<InstructionOperand>>map(register -> List.of(new RegisterOperand(register)))
                .orElseGet(List::of);
    }
}
