package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import java.util.Objects;

/**
 * Representa la instrucción que toma un registro como origen para el acumulador.
 */
public record LoadInstruction(RegisterName source) implements Instruction {

    // Valida el registro que proporcionará el valor para AC.
    public LoadInstruction {
        Objects.requireNonNull(source, "source must not be null");
    }

    @Override
    // Identifica la operación semántica representada.
    public Opcode opcode() {
        return Opcode.LOAD;
    }
}
