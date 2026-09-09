package io.github.rajami1205.osimulator.model.instruction.binary;

import java.util.List;
import java.util.Objects;

/**
 * Immutable ordered binary representation of one semantic instruction.
 */
public record EncodedInstruction(List<BinaryWord> words) {

    public EncodedInstruction {
        Objects.requireNonNull(words, "words must not be null");

        if (words.isEmpty()) {
            throw new IllegalArgumentException("words must not be empty");
        }

        words = List.copyOf(words);
    }
}
