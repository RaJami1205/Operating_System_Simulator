package io.github.rajami1205.osimulator.model.storage;

import io.github.rajami1205.osimulator.model.instruction.Instruction;
import java.util.Objects;

/** Instrucción semántica inmutable usada en programas almacenados e imágenes completas de SWAP. */
public record StoredInstructionContent(Instruction instruction) implements StorageContent {
    /** Exige una instrucción no nula para el contenido almacenado. */
    public StoredInstructionContent {
        Objects.requireNonNull(instruction, "instruction must not be null");
    }
}
