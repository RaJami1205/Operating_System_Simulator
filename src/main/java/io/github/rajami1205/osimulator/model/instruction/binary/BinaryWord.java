package io.github.rajami1205.osimulator.model.instruction.binary;

import java.util.Objects;

/**
 * Immutable sequence of binary digits whose width is determined by its value.
 */
public record BinaryWord(String bits) {

    public BinaryWord {
        Objects.requireNonNull(bits, "bits must not be null");

        if (bits.isEmpty()) {
            throw new IllegalArgumentException("bits must not be empty");
        }

        for (int index = 0; index < bits.length(); index++) {
            char bit = bits.charAt(index);
            if (bit != '0' && bit != '1') {
                throw new IllegalArgumentException(
                        "bits must contain only '0' and '1': " + bits
                );
            }
        }
    }

    public int width() {
        return bits.length();
    }
}
