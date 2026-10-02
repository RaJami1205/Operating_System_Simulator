package io.github.rajami1205.osimulator.model.storage;

import java.util.Objects;

/**
 * Nombre, inicio físico, longitud lógica y clase de entrada. SecondaryStorage conserva aparte la capacidad
 * reservada, que puede superar la longitud del texto.
 */
public record FileIndexEntry(String name, int startAddress, int length, FileEntryKind kind) implements StorageContent {
    /** Valida nombre, dirección, longitud y tipo; el constructor compatible representa una entrada PROGRAM. */
    public FileIndexEntry(String name, int startAddress, int length) {
        this(name, startAddress, length, FileEntryKind.PROGRAM);
    }
    /** Valida nombre, dirección, longitud y tipo; el constructor compatible representa una entrada PROGRAM. */
    public FileIndexEntry {
        validateName(name);
        Objects.requireNonNull(kind, "kind must not be null");
        if (startAddress < 0 || length < (kind == FileEntryKind.PROGRAM ? 1 : 0)
                || (long) startAddress + Math.max(1, length) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid storage entry range");
        }
    }

    /** Rechaza nombres nulos o en blanco; preserva texto y distingue nombres por igualdad exacta. */
    static void validateName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.isBlank()) throw new IllegalArgumentException("Program name must not be blank");
    }
}
