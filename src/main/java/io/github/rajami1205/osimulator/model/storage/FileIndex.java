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

    public FileIndex(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Index capacity must be positive");
        this.capacity = capacity;
    }

    public int capacity() { return capacity; }

    public Optional<FileIndexEntry> find(String name) {
        FileIndexEntry.validateName(name);
        return slots.values().stream().filter(entry -> entry.name().equals(name)).findFirst();
    }

    public List<FileIndexEntry> entries() { return List.copyOf(slots.values()); }

    public StorageContent read(int slot) {
        if (slot < 0 || slot >= capacity) throw new IndexOutOfBoundsException("Invalid index slot: " + slot);
        if (slots.containsKey(slot)) return slots.get(slot);
        return EmptyStorageContent.INSTANCE;
    }

    void validatePublication(String name) {
        if (find(name).isPresent()) throw new StorageException("Duplicate program name: " + name);
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

    public boolean remove(String name) {
        FileIndexEntry.validateName(name);
        var slot = slots.entrySet().stream().filter(entry -> entry.getValue().name().equals(name))
                .map(java.util.Map.Entry::getKey).findFirst();
        slot.ifPresent(slots::remove);
        return slot.isPresent();
    }

    public void reset() { slots.clear(); }
}
