package io.github.rajami1205.osimulator.model.instruction.binary;

import java.util.Objects;

/**
 * Representa una secuencia inmutable de dígitos binarios cuyo valor determina su ancho.
 */
public record BinaryWord(String bits) {

    // Valida una secuencia no vacía formada exclusivamente por dígitos binarios.
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

    // Expone el número de bits de esta palabra.
    public int width() {
        return bits.length();
    }
}
