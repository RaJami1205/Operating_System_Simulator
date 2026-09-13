package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.exception.InvalidImmediateValueException;
import java.util.Objects;

/**
 * Representa la instrucción que asigna un inmediato a un registro destino al ejecutarse.
 */
public record MovInstruction(RegisterName destination, int immediate) implements Instruction {

    private static final int MIN_IMMEDIATE_VALUE = -127;
    private static final int MAX_IMMEDIATE_VALUE = 127;

    // Valida el registro destino y el rango lógico del inmediato.
    public MovInstruction {
        Objects.requireNonNull(destination, "destination must not be null");

        if (immediate < MIN_IMMEDIATE_VALUE || immediate > MAX_IMMEDIATE_VALUE) {
            throw new InvalidImmediateValueException(
                    "Immediate value must be between "
                            + MIN_IMMEDIATE_VALUE
                            + " and "
                            + MAX_IMMEDIATE_VALUE
                            + ": "
                            + immediate
            );
        }
    }

    @Override
    // Identifica la operación semántica representada.
    public Opcode opcode() {
        return Opcode.MOV;
    }
}
