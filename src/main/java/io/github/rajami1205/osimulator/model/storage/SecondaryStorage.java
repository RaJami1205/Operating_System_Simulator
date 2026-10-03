package io.github.rajami1205.osimulator.model.storage;

import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.storage.exception.StorageException;
import io.github.rajami1205.osimulator.model.storage.exception.InvalidStorageReleaseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Storage simulado de sesión: FileIndex compartido por PROGRAM/USER_FILE, área de datos y VIRTUAL_MEMORY reservado
 * para imágenes completas. No accede al filesystem anfitrión ni implementa paging.
 */
public final class SecondaryStorage {
    private final int totalPositions;
    private final int virtualMemoryPositions;
    private final int virtualMemoryStart;
    private final FileIndex index;
    private final StorageContent[] data;
    private final StorageContent[] virtualMemory;
    private final FirstFitStorageAllocator virtualMemoryAllocator;
    private final FirstFitStorageAllocator allocator;
    private final Map<String, StorageAllocation> allocations = new HashMap<>();

    /**
     * Valida capacidades y separa índice, datos y VIRTUAL_MEMORY; reserva para índice la mitad previa a VIRTUAL_MEMORY como
     * política local del proyecto.
     */
    public SecondaryStorage(int totalPositions, int virtualMemoryPositions) {
        if (totalPositions < 128 || virtualMemoryPositions < 64 || virtualMemoryPositions >= totalPositions) {
            throw new IllegalArgumentException("Invalid Secondary Storage / Virtual Memory capacities");
        }
        this.totalPositions = totalPositions;
        this.virtualMemoryPositions = virtualMemoryPositions;
        virtualMemoryStart = totalPositions - virtualMemoryPositions;
        // Política del proyecto, no una capacidad de índice fijada por el enunciado.
        index = new FileIndex(Math.max(1, virtualMemoryStart / 2));
        data = new StorageContent[virtualMemoryStart - index.capacity()];
        allocator = new FirstFitStorageAllocator(index.capacity(), virtualMemoryStart);
        Arrays.fill(data, EmptyStorageContent.INSTANCE);
        virtualMemory = new StorageContent[virtualMemoryPositions];
        virtualMemoryAllocator = new FirstFitStorageAllocator(virtualMemoryStart, totalPositions);
        Arrays.fill(virtualMemory, EmptyStorageContent.INSTANCE);
    }

    /** Devuelve posiciones totales de índice, datos y VIRTUAL_MEMORY de la sesión. */
    public int size() { return totalPositions; }
    /** Devuelve la capacidad física reservada al área VIRTUAL_MEMORY, no memoria paginada. */
    public int virtualMemoryPositions() { return virtualMemoryPositions; }
    /** Devuelve slots iniciales reservados para FileIndex. */
    public int indexPositions() { return index.capacity(); }
    /** Devuelve el primer address físico de PROGRAM_DATA después del índice. */
    public int dataStart() { return index.capacity(); }
    /** Devuelve el límite exclusivo del área de datos, que coincide con el inicio de VIRTUAL_MEMORY. */
    public int dataEndExclusive() { return virtualMemoryStart; }
    /** Devuelve totalPositions menos virtualMemoryPositions, inicio físico del área VIRTUAL_MEMORY. */
    public int swapStart() { return virtualMemoryStart; }

    /** Valida el address físico y resuelve FILE_INDEX, PROGRAM_DATA o VIRTUAL_MEMORY. */
    public StorageRegion regionOf(int address) {
        if (address < 0 || address >= totalPositions) {
            throw new IndexOutOfBoundsException("Invalid storage address: " + address);
        }
        if (address < dataStart()) return StorageRegion.FILE_INDEX;
        return address < virtualMemoryStart ? StorageRegion.PROGRAM_DATA : StorageRegion.VIRTUAL_MEMORY;
    }

    /** El índice y cada región tienen una única representación física. */
    public StorageContent read(int address) {
        return switch (regionOf(address)) {
            case FILE_INDEX -> index.read(address);
            case PROGRAM_DATA -> data[address - dataStart()];
            case VIRTUAL_MEMORY -> virtualMemory[address - virtualMemoryStart];
        };
    }

    /** Crea una vista inmutable con address, región y contenido de una posición válida. */
    public StorageCell cell(int address) {
        return new StorageCell(address, regionOf(address), read(address));
    }

    /** Busca por nombre exclusivamente entradas PROGRAM; USER_FILE no se presenta como programa. */
    public Optional<FileIndexEntry> findProgram(String name) { return index.find(name).filter(entry -> entry.kind() == FileEntryKind.PROGRAM); }
    /** Devuelve la vista inmutable del índice compartido, en orden físico de slot. */
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
            var entry = new FileIndexEntry(name, allocation.base(), allocation.size(), FileEntryKind.PROGRAM);
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

    /**
     * Recupera una lista inmutable de instrucciones según el índice; rechaza programa ausente o contenido
     * heterogéneo inválido.
     */
    public List<Instruction> readProgram(String name) {
        var entry = findProgram(name).orElseThrow(() -> new StorageException("Unknown program: " + name));
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
        if (findProgram(name).isEmpty()) return false;
        var allocation = allocations.get(name);
        allocator.release(allocation);
        clear(allocation);
        allocations.remove(name);
        index.remove(name);
        return true;
    }

    /** Exige que el nombre exista en el índice como USER_FILE, sin aceptar un PROGRAM homónimo. */
    public FileIndexEntry userFile(String name) {
        var entry = index.find(name).orElseThrow(() -> new StorageException("Unknown user file: " + name));
        if (entry.kind() != FileEntryKind.USER_FILE) throw new StorageException("Entry is not a user file: " + name);
        return entry;
    }

    /**
     * Reserva una posición mínima y publica archivo de longitud cero; revierte índice, handle y contenido
     * si falla la publicación.
     */
    public FileIndexEntry createUserFile(String name) {
        index.validatePublication(name);
        var allocation = allocator.allocate(1);
        boolean committed = false;
        try {
            var entry = new FileIndexEntry(name, allocation.base(), 0, FileEntryKind.USER_FILE);
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

    /** Reconstruye texto de la longitud lógica indexada y rechaza contenido distinto de UserFileContent. */
    public String readUserFile(String name) {
        var entry = userFile(name);
        var result = new StringBuilder(entry.length());
        for (int offset = 0; offset < entry.length(); offset++) {
            if (!(read(entry.startAddress() + offset) instanceof UserFileContent content)) {
                throw new StorageException("User file contains non-text content");
            }
            result.append(content.value());
        }
        return result.toString();
    }

    /**
     * Reemplaza todo el texto con rollback de publicación; al reducirlo conserva capacidad previa y al
     * crecer reserva primero otro bloque.
     */
    public void writeUserFile(String name, String content) {
        var previous = userFile(name);
        Objects.requireNonNull(content, "content must not be null");
        StorageContent[] replacement = new StorageContent[Math.max(1, content.length())];
        Arrays.fill(replacement, EmptyStorageContent.INSTANCE);
        for (int i = 0; i < content.length(); i++) replacement[i] = new UserFileContent(content.charAt(i));
        var oldAllocation = requireAllocation(name);
        if (replacement.length <= oldAllocation.size()) {
            var entry = new FileIndexEntry(name, oldAllocation.base(), content.length(), FileEntryKind.USER_FILE);
            int start = oldAllocation.base() - dataStart();
            var oldContents = Arrays.copyOfRange(data, start, start + oldAllocation.size());
            boolean committed = false;
            try {
                System.arraycopy(replacement, 0, data, start, replacement.length);
                Arrays.fill(data, start + replacement.length, start + oldAllocation.size(), EmptyStorageContent.INSTANCE);
                index.replace(entry);
                committed = true;
            } finally {
                if (!committed) System.arraycopy(oldContents, 0, data, start, oldContents.length);
            }
            return;
        }
        var allocation = allocator.allocate(replacement.length);
        boolean committed = false;
        try {
            var entry = new FileIndexEntry(name, allocation.base(), content.length(), FileEntryKind.USER_FILE);
            System.arraycopy(replacement, 0, data, allocation.base() - dataStart(), replacement.length);
            index.replace(entry);
            allocations.put(name, allocation);
            committed = true;
        } finally {
            if (!committed) {
                index.replace(previous);
                clear(allocation);
                allocator.release(allocation);
            }
        }
        // El handle original ya validado permanece activo hasta publicar correctamente el reemplazo.
        allocator.release(oldAllocation);
        clear(oldAllocation);
    }

    /** Valida USER_FILE y su handle activo antes de liberar datos y retirar metadata del índice. */
    public void deleteUserFile(String name) {
        userFile(name);
        var allocation = requireAllocation(name);
        allocator.release(allocation);
        clear(allocation);
        allocations.remove(name);
        index.remove(name);
    }

    /** Exige el handle activo asociado al nombre; no reconstruye identidad usando su dirección. */
    private StorageAllocation requireAllocation(String name) {
        var allocation = allocations.get(name);
        if (!allocator.isActive(allocation)) throw new StorageException("Missing active allocation: " + name);
        return allocation;
    }

    /** Reserva un bloque contiguo dentro del allocator VIRTUAL_MEMORY separado del área de programas y archivos. */
    public StorageAllocation allocateSwap(int size) { return virtualMemoryAllocator.allocate(size); }

    /** Exige handle original activo dentro de VIRTUAL_MEMORY; rechaza stale/foreign antes de acceder al contenido. */
    private void validateSwap(StorageAllocation allocation) {
        if (!virtualMemoryAllocator.isActive(allocation) || allocation.base() < virtualMemoryStart
                || allocation.endExclusive() > size()) {
            throw new InvalidStorageReleaseException(
                    "VIRTUAL_MEMORY requires an active original allocation in its bounded region");
        }
    }

    /** Valida handle y tamaño exacto de la imagen completa antes de publicar instrucciones en VIRTUAL_MEMORY. */
    public void writeSwapBlock(StorageAllocation allocation, List<Instruction> image) {
        validateSwap(allocation);
        var instructions = List.copyOf(Objects.requireNonNull(image, "image must not be null"));
        if (instructions.size() != allocation.size()) throw new StorageException("VIRTUAL_MEMORY image size mismatch");
        var contents = instructions.stream().map(StoredInstructionContent::new).toArray(StoredInstructionContent[]::new);
        System.arraycopy(contents, 0, virtualMemory, allocation.base() - virtualMemoryStart, contents.length);
    }

    /** Devuelve copia inmutable de una imagen completa tras validar identidad y contenido de cada posición. */
    public List<Instruction> readSwapBlock(StorageAllocation allocation) {
        validateSwap(allocation);
        var image = new ArrayList<Instruction>(allocation.size());
        for (int offset = 0; offset < allocation.size(); offset++) {
            if (!(virtualMemory[allocation.base() - virtualMemoryStart + offset] instanceof StoredInstructionContent content)) {
                throw new StorageException("Incomplete or invalid VIRTUAL_MEMORY image");
            }
            image.add(content.instruction());
        }
        return List.copyOf(image);
    }

    /** Valida la reserva original, la libera y vacía su contenido sin afectar PROGRAM_DATA. */
    public void releaseSwap(StorageAllocation allocation) {
        validateSwap(allocation);
        virtualMemoryAllocator.release(allocation);
        Arrays.fill(virtualMemory, allocation.base() - virtualMemoryStart, allocation.endExclusive() - virtualMemoryStart,
                EmptyStorageContent.INSTANCE);
    }

    /** Vacía datos, VIRTUAL_MEMORY e índice y reinicia ambos allocators, invalidando todos los handles de sesión. */
    public void reset() {
        Arrays.fill(data, EmptyStorageContent.INSTANCE);
        Arrays.fill(virtualMemory, EmptyStorageContent.INSTANCE);
        virtualMemoryAllocator.reset();
        index.reset();
        allocations.clear();
        allocator.reset();
    }

    /** Vacía contenido del rango PROGRAM_DATA indicado; la liberación del handle se realiza por separado. */
    private void clear(StorageAllocation allocation) {
        Arrays.fill(data, allocation.base() - dataStart(), allocation.endExclusive() - dataStart(),
                EmptyStorageContent.INSTANCE);
    }
}
