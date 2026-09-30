package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.operand.ImmediateOperand;

import java.util.Objects;
import io.github.rajami1205.osimulator.model.cpu.MovDestination;
import io.github.rajami1205.osimulator.model.cpu.ServiceRegister;
import io.github.rajami1205.osimulator.model.instruction.operand.TextOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.ServiceRegisterOperand;
import java.util.List;
import io.github.rajami1205.osimulator.model.instruction.operand.InstructionOperand;
import io.github.rajami1205.osimulator.model.instruction.operand.RegisterOperand;

/**
 * Asigna un inmediato o el valor de un registro a un registro destino.
 */
public record MovInstruction(MovDestination destination, InstructionOperand source) implements Instruction {

    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(1);

    // Los operandos tipados ya validan su contenido.
    public MovInstruction {
        Objects.requireNonNull(destination, "destination must not be null");

        Objects.requireNonNull(source, "source must not be null");
        boolean valid = switch (destination) {
            case RegisterName register -> source instanceof RegisterOperand || source instanceof ImmediateOperand
                    || register == RegisterName.DX && source instanceof TextOperand;
            case ServiceRegister service -> service == ServiceRegister.AH
                    ? source instanceof ImmediateOperand : source instanceof TextOperand;
        };
        if (!valid) {
            throw new IllegalArgumentException("Invalid MOV destination/source combination");
        }
    }

    public MovInstruction(MovDestination destination, int immediate) {
        this(Objects.requireNonNull(destination, "destination must not be null"), new ImmediateOperand(immediate));
    }

    public MovInstruction(RegisterName destination, RegisterName source) {
        this(destination, new RegisterOperand(source));
    }

    @Override
    // Identifica la operación semántica representada.
    public Opcode opcode() {
        return Opcode.MOV;
    }
    @Override
    public List<InstructionOperand> operands() {
        InstructionOperand target = switch (destination) {
            case RegisterName register -> new RegisterOperand(register);
            case ServiceRegister service -> new ServiceRegisterOperand(service);
        };
        return List.of(target, source);
    }

    @Override
    public ExecutionWeight executionWeight() {
        return EXECUTION_WEIGHT;
    }
}
