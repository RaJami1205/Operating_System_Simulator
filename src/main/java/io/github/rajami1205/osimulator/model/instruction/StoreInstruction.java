package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import java.util.Objects;

/**
 * Representa la instrucción que toma un registro como destino del acumulador.
 */
public record StoreInstruction(RegisterName destination) implements Instruction {

    // Valida el registro que recibirá el valor de AC.
    public StoreInstruction {
        Objects.requireNonNull(destination, "destination must not be null");
    }

    @Override
    // Identifica la operación semántica representada.
    public Opcode opcode() {
        return Opcode.STORE;
    }
}
