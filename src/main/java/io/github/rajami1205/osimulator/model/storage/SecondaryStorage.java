package io.github.rajami1205.osimulator.model.storage;

import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.storage.exception.StorageException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Almacenamiento simulado de sesión; no accede al filesystem anfitrión. */
public final class SecondaryStorage {
    private final int totalPositions;
    private final int virtualMemoryPositions;
    private final int swapStart;
    private final FileIndex index;
    private final StorageContent[] data;
    private final FirstFitStorageAllocator allocator;
    private final Map<String, StorageAllocation> allocations = new HashMap<>();

    public SecondaryStorage(int totalPositions, int virtualMemoryPositions) {
        if (totalPositions < 128 || virtualMemoryPositions < 64 || virtualMemoryPositions >= totalPositions) {
            throw new IllegalArgumentException("Invalid Secondary Storage / Virtual Memory capacities");
        }
        this.totalPositions = totalPositions;
        this.virtualMemoryPositions = virtualMemoryPositions;
        swapStart = totalPositions - virtualMemoryPositions;
        // Política del proyecto, no una capacidad de índice fijada por el enunciado.
        index = new FileIndex(Math.max(1, swapStart / 2));
        data = new StorageContent[swapStart - index.capacity()];
        allocator = new FirstFitStorageAllocator(index.capacity(), swapStart);
        Arrays.fill(data, EmptyStorageContent.INSTANCE);
    }

    public int size() { return totalPositions; }
    public int virtualMemoryPositions() { return virtualMemoryPositions; }
    public int indexPositions() { return index.capacity(); }
    public int dataStart() { return index.capacity(); }
    public int dataEndExclusive() { return swapStart; }
    public int swapStart() { return swapStart; }

    public StorageRegion regionOf(int address) {
        if (address < 0 || address >= totalPositions) {
            throw new IndexOutOfBoundsException("Invalid storage address: " + address);
        }
        if (address < dataStart()) return StorageRegion.FILE_INDEX;
        return address < swapStart ? StorageRegion.PROGRAM_DATA : StorageRegion.SWAP;
    }

    /** El índice tiene una única representación; Swap permanece vacío y reservado. */
    public StorageContent read(int address) {
        return switch (regionOf(address)) {
            case FILE_INDEX -> index.read(address);
            case PROGRAM_DATA -> data[address - dataStart()];
            case SWAP -> EmptyStorageContent.INSTANCE;
        };
    }

    public StorageCell cell(int address) {
        return new StorageCell(address, regionOf(address), read(address));
    }

    public Optional<FileIndexEntry> findProgram(String name) { return index.find(name); }
    public List<FileIndexEntry> entries() { return index.entries(); }

    /** Publica sólo después de escribir; cualquier fallo posterior a reservar revierte la operación. */
    public FileIndexEntry storeProgram(String name, List<? extends Instruction> instructions) {
        FileIndexEntry.validateName(name);
        var program = List.copyOf(Objects.requireNonNull(instructions, "instructions must not be null"));
        if (program.isEmpty()) throw new StorageException("Program must not be empty");
        var contents = program.stream().map(StoredInstructionContent::new).toArray(StoredInstructionContent[]::new);
        index.validatePublication(name);
        var allocation = allocator.allocate(contents.length);
        boolean committed = false;
        try {
            var entry = new FileIndexEntry(name, allocation.base(), allocation.size());
            System.arraycopy(contents, 0, data, allocation.base() - dataStart(), contents.length);
            allocations.put(name, allocation);
            index.publish(entry);
            committed = true;
            return entry;
        } finally {
            if (!committed) {
                index.remove(name);
                allocations.remove(name);
                clear(allocation);
                allocator.release(allocation);
            }
        }
    }

    public List<Instruction> readProgram(String name) {
        var entry = index.find(name).orElseThrow(() -> new StorageException("Unknown program: " + name));
        var result = new ArrayList<Instruction>(entry.length());
        for (int offset = 0; offset < entry.length(); offset++) {
            if (read(entry.startAddress() + offset) instanceof StoredInstructionContent content) {
                result.add(content.instruction());
            } else {
                throw new StorageException("Stored program block contains non-instruction content");
            }
        }
        return List.copyOf(result);
    }

    /** Libera el handle original; no reconstruye identidades a partir de direcciones. */
    public boolean removeProgram(String name) {
        if (index.find(name).isEmpty()) return false;
        var allocation = allocations.get(name);
        allocator.release(allocation);
        clear(allocation);
        allocations.remove(name);
        index.remove(name);
        return true;
    }

    public void reset() {
        Arrays.fill(data, EmptyStorageContent.INSTANCE);
        index.reset();
        allocations.clear();
        allocator.reset();
    }

    private void clear(StorageAllocation allocation) {
        Arrays.fill(data, allocation.base() - dataStart(), allocation.endExclusive() - dataStart(),
                EmptyStorageContent.INSTANCE);
    }
}
