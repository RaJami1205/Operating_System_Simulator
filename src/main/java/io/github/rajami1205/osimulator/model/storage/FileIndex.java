package io.github.rajami1205.osimulator.model.storage;

import io.github.rajami1205.osimulator.model.storage.exception.StorageException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** Fuente canónica de las entradas físicas del índice, ordenadas por slot. */
public final class FileIndex {
    private final int capacity;
    private final TreeMap<Integer, FileIndexEntry> slots = new TreeMap<>();

    /** Exige capacidad positiva y comienza sin entradas publicadas. */
    public FileIndex(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Index capacity must be positive");
        this.capacity = capacity;
    }

    /** Devuelve el número reservado de slots, no la cantidad de entradas publicadas. */
    public int capacity() { return capacity; }

    /** Busca el nombre validado entre PROGRAM y USER_FILE por igualdad textual exacta, sin normalizarlo. */
    public Optional<FileIndexEntry> find(String name) {
        FileIndexEntry.validateName(name);
        return slots.values().stream().filter(entry -> entry.name().equals(name)).findFirst();
    }

    /** Devuelve copia inmutable de entradas en orden de slot físico. */
    public List<FileIndexEntry> entries() { return List.copyOf(slots.values()); }

    /** Valida el slot y devuelve su entrada o EmptyStorageContent si no está ocupado. */
    public StorageContent read(int slot) {
        if (slot < 0 || slot >= capacity) throw new IndexOutOfBoundsException("Invalid index slot: " + slot);
        if (slots.containsKey(slot)) return slots.get(slot);
        return EmptyStorageContent.INSTANCE;
    }

    /** Rechaza nombres duplicados o índice lleno antes de reservar contenido. */
    void validatePublication(String name) {
        if (find(name).isPresent()) throw new StorageException("Duplicate storage name: " + name);
        if (slots.size() == capacity) throw new StorageException("File Index is full");
    }

    /** Publica en el primer slot libre, sin desplazar entradas existentes. */
    public int publish(FileIndexEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        validatePublication(entry.name());
        int slot = 0;
        while (slots.containsKey(slot)) slot++;
        slots.put(slot, entry);
        return slot;
    }

    /** Sustituye metadata en el mismo slot sin renombrar la entrada ni cambiar PROGRAM/USER_FILE. */
    public void replace(FileIndexEntry replacement) {
        Objects.requireNonNull(replacement, "replacement must not be null");
        var slot = slots.entrySet().stream().filter(item -> item.getValue().name().equals(replacement.name()))
                .findFirst().orElseThrow(() -> new StorageException("Unknown index entry: " + replacement.name()));
        if (slot.getValue().kind() != replacement.kind()) throw new StorageException("Entry kind cannot change");
        slots.put(slot.getKey(), replacement);
    }

    /**
     * Retira la entrada por nombre y devuelve si existía; la liberación de datos corresponde a
     * SecondaryStorage.
     */
    public boolean remove(String name) {
        FileIndexEntry.validateName(name);
        var slot = slots.entrySet().stream().filter(entry -> entry.getValue().name().equals(name))
                .map(java.util.Map.Entry::getKey).findFirst();
        slot.ifPresent(slots::remove);
        return slot.isPresent();
    }

    /** Vacía slots del índice sin modificar por sí mismo allocations o contenido de datos. */
    public void reset() { slots.clear(); }
}
