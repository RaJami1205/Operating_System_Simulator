package io.github.rajami1205.osimulator.model.storage;

import java.util.Objects;

/** length delimita el bloque, sin depender del contenido de posiciones vecinas. */
public record FileIndexEntry(String name, int startAddress, int length) implements StorageContent {
    public FileIndexEntry {
        validateName(name);
        if (startAddress < 0 || length < 1 || (long) startAddress + length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid stored program range");
        }
    }

    static void validateName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) throw new IllegalArgumentException("Program name must not be blank");
    }
}
