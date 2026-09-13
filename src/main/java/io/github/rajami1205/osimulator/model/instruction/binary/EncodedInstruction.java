package io.github.rajami1205.osimulator.model.instruction.binary;

import java.util.List;
import java.util.Objects;

/**
 * Representa una instrucción semántica mediante una secuencia binaria ordenada e inmutable.
 */
public record EncodedInstruction(List<BinaryWord> words) {

    // Conserva una copia inmutable y no vacía de las palabras de la instrucción.
    public EncodedInstruction {
        Objects.requireNonNull(words, "words must not be null");

        if (words.isEmpty()) {
            throw new IllegalArgumentException("words must not be empty");
        }

        words = List.copyOf(words);
    }
}
