package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.cpu.CpuValueRange;
import io.github.rajami1205.osimulator.model.instruction.exception.InvalidImmediateValueException;
import java.util.Objects;

/**
 * Representa la instrucción que asigna un inmediato a un registro destino al ejecutarse.
 */
public record MovInstruction(RegisterName destination, int immediate) implements Instruction {

    // Valida el registro destino y el rango lógico del inmediato.
    public MovInstruction {
        Objects.requireNonNull(destination, "destination must not be null");

        if (!CpuValueRange.contains(immediate)) {
            throw new InvalidImmediateValueException(
                    "Immediate value must be between "
                            + CpuValueRange.MIN_VALUE
                            + " and "
                            + CpuValueRange.MAX_VALUE
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
