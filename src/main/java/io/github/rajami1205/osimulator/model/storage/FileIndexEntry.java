package io.github.rajami1205.osimulator.model.storage;

import java.util.Objects;

/** Logical content length; physical allocation capacity is owned separately by SecondaryStorage. */
public record FileIndexEntry(String name, int startAddress, int length, FileEntryKind kind) implements StorageContent {
    /** Compatibility constructor for stored programs. */
    public FileIndexEntry(String name, int startAddress, int length) {
        this(name, startAddress, length, FileEntryKind.PROGRAM);
    }
    public FileIndexEntry {
        validateName(name);
        Objects.requireNonNull(kind, "kind must not be null");
        if (startAddress < 0 || length < (kind == FileEntryKind.PROGRAM ? 1 : 0)
                || (long) startAddress + Math.max(1, length) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid storage entry range");
        }
    }

    static void validateName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) throw new IllegalArgumentException("Program name must not be blank");
    }
}
