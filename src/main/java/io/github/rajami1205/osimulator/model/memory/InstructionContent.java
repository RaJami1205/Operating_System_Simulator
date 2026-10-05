package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.instruction.Instruction;
import java.util.Objects;

/** Instrucción semántica almacenada en una celda USER; no contiene codificación binaria. */
public record InstructionContent(Instruction instruction) implements MemoryContent {
    /** Exige una instrucción no nula como contenido de memoria. */
    public InstructionContent {
        Objects.requireNonNull(instruction, "instruction must not be null");
    }
}
