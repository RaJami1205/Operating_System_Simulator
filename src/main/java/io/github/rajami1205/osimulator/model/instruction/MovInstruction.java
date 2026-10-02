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
 * Transfiere números entre registros generales o desde inmediatos; permite texto en DX/AL y un inmediato
 * numérico en AH.
 */
public record MovInstruction(MovDestination destination, InstructionOperand source) implements Instruction {

    private static final ExecutionWeight EXECUTION_WEIGHT = new ExecutionWeight(1);

    /** Exige destino y fuente no nulos, en ese orden, y rechaza combinaciones de tipos no admitidas por MOV. */
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

    /** Valida primero el destino y después el inmediato signed 16-bit, conservando InvalidImmediateValueException. */
    public MovInstruction(MovDestination destination, int immediate) {
        this(Objects.requireNonNull(destination, "destination must not be null"), new ImmediateOperand(immediate));
    }

    /** Construye MOV entre registros generales; el wrapper valida la fuente antes de delegar al constructor canónico. */
    public MovInstruction(RegisterName destination, RegisterName source) {
        this(destination, new RegisterOperand(source));
    }

    /** Identifica MOV para dispatch y representación semántica. */
    @Override
    public Opcode opcode() {
        return Opcode.MOV;
    }
    /** Devuelve la vista inmutable y ordenada de operandos, derivada del estado del record. */
    @Override
    public List<InstructionOperand> operands() {
        InstructionOperand target = switch (destination) {
            case RegisterName register -> new RegisterOperand(register);
            case ServiceRegister service -> new ServiceRegisterOperand(service);
        };
        return List.of(target, source);
    }

    /** Devuelve el peso estático en CPU ticks de MOV, sin almacenar progreso. */
    @Override
    public ExecutionWeight executionWeight() {
        return EXECUTION_WEIGHT;
    }
}
