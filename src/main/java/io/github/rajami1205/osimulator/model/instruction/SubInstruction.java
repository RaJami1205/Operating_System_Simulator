package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import java.util.Objects;

/**
 * Representa la instrucción que toma un registro como operando de resta.
 */
public record SubInstruction(RegisterName source) implements Instruction {

    // Valida el registro cuyo valor se restará de AC.
    public SubInstruction {
        Objects.requireNonNull(source, "source must not be null");
    }

    @Override
    // Identifica la operación semántica representada.
    public Opcode opcode() {
        return Opcode.SUB;
    }
}
