package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryAddressException;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessMemoryBounds;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

/**
 * Memoria física tipada con KERNEL First-Fit y estrategia USER independiente. La disponibilidad
 * pertenece a los allocators, no a EmptyContent; el fetch usa el handle canónico.
 */
public final class MainMemory {
    private final MemoryConfiguration configuration;
    private final MemoryMode memoryMode;
    private final MemoryContent[] positions;
    private final MemoryAllocator kernel;
    private final MemoryAllocator user;
    private final UserAllocationSupport userSupport;
    private final FrameAllocator frames;
    private final Optional<PagingConfiguration> paging;
    private final Map<Integer, PageTable> pageTables = new HashMap<>();
    private Object userGeneration = new Object();
    private UserReservation pendingUser;

    /** Crea celdas vacías y dos allocators bounded independientes según MemoryConfiguration. */
    public MainMemory(MemoryConfiguration configuration) {
        this(configuration, UserAllocationStrategy.FIRST_FIT);
    }

    public MainMemory(MemoryConfiguration configuration, UserAllocationStrategy strategy) {
        this(configuration, strategy, MemoryMode.DYNAMIC, Optional.empty());
    }

    public MainMemory(MemoryConfiguration configuration, UserAllocationStrategy strategy,
            MemoryMode mode, Optional<FixedPartitionConfiguration> fixed) {
        this(configuration, strategy, mode, fixed,
                mode == MemoryMode.PAGING ? Optional.of(PagingConfiguration.defaults()) : Optional.empty());
    }

    public MainMemory(MemoryConfiguration configuration, UserAllocationStrategy strategy,
            MemoryMode mode, Optional<FixedPartitionConfiguration> fixed, Optional<PagingConfiguration> paging) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(strategy, "strategy must not be null");
        FixedPartitionConfiguration.validateMode(configuration, mode, fixed);
        this.paging = Objects.requireNonNull(paging);
        if ((mode == MemoryMode.PAGING) != paging.isPresent()) throw new IllegalArgumentException("Paging configuration and mode disagree");
        paging.ifPresent(value -> value.validate(configuration));
        memoryMode = mode;
        positions = new MemoryContent[configuration.totalPositions()];
        kernel = new FirstFitMemoryAllocator(MemoryRegion.KERNEL, 0, configuration.kernelReservedPositions());
        user = mode == MemoryMode.PAGING ? null : mode == MemoryMode.DYNAMIC
                ? new DynamicMemoryAllocator(MemoryRegion.USER, configuration.userStartAddress(), configuration.userPositions(), strategy)
                : new FixedPartitionMemoryAllocator(configuration, fixed.orElseThrow());
        userSupport = new UserAllocationSupport(user);
        frames = paging.map(value -> new FrameAllocator(configuration, value)).orElse(null);
        Arrays.fill(positions, EmptyContent.INSTANCE);
    }

    /** Expone la configuración inmutable que separa Kernel y USER. */
    public MemoryConfiguration configuration() { return configuration; }
    public MemoryMode memoryMode() { return memoryMode; }
    /** No consulta ocupación: distingue incompatibilidad del layout y capacidad temporal. */
    public void validateProgramLength(int length) {
        if (frames != null && paging.orElseThrow().requiredFrames(length) > frames.count()) {
            throw new io.github.rajami1205.osimulator.model.memory.exception.IncompatibleProgramException("Program requires more frames than the USER pool");
        }
        userSupport.validateProgramLength(length);
    }
    public List<FixedPartition.Occupancy> partitions() { return userSupport.partitions(); }
    /** Devuelve el total de posiciones físicas simuladas, incluidas ambas regiones. */
    public int size() { return positions.length; }
    /** Valida la dirección física y devuelve Kernel o USER según la configuración. */
    public MemoryRegion regionOf(int address) { return configuration.regionOf(address); }

    /**
     * Lee contenido tipado por dirección física validada; es observación, no prueba de ownership del
     * proceso.
     */
    public MemoryContent read(int address) {
        regionOf(address);
        return positions[address];
    }

    /** Construye la vista de dirección, región y contenido de una posición física válida. */
    public MemoryCell cell(int address) {
        return new MemoryCell(address, regionOf(address), read(address));
    }

    /** Indica ausencia de contenido; una posición vacía puede seguir reservada por su allocator. */
    public boolean isEmpty(int address) { return read(address) == EmptyContent.INSTANCE; }
    /** Reserva USER confirmada; no puede intercalarse con una publicación provisional. */
    public MemoryAllocation allocateUser(int size) {
        requireNoPendingUser();
        requireContiguousMode();
        return user.allocate(size);
    }

    /** Token opaco de una operación USER, ligado al allocator y a su generación. */
    public static final class UserReservation {
        private final MemoryAllocator allocator;
        private final Object generation;
        private final ResidentImage image;
        private final Runnable rollback;

        private UserReservation(MemoryAllocator allocator, Object generation,
                ResidentImage image, Runnable rollback) {
            this.allocator = allocator;
            this.generation = generation;
            this.image = image;
            this.rollback = rollback;
        }

        public ResidentImage image() { return image; }
        public MemoryAllocation allocation() {
            if (image instanceof MemoryAllocation allocation) return allocation;
            throw new IllegalStateException("Paged image has no contiguous allocation");
        }
    }

    /** Mantiene una única reserva provisional hasta confirmar, cancelar o resetear la región. */
    public UserReservation reserveUser(int size) {
        requireNoPendingUser();
        requireContiguousMode();
        Runnable rollback = userSupport.captureRollback();
        var allocation = user.allocate(size);
        pendingUser = new UserReservation(user, userGeneration, allocation, rollback);
        return pendingUser;
    }

    public void confirmUser(UserReservation reservation) {
        validateReservation(reservation);
        pendingUser = null;
    }

    /** Libera sólo esta reserva; nunca restaura mapas ni reactiva identidades anteriores. */
    public void cancelUser(UserReservation reservation) {
        validateReservation(reservation);
        releaseImage(reservation.image);
        reservation.rollback.run();
        pendingUser = null;
    }

    private void validateReservation(UserReservation reservation) {
        if (reservation == null || reservation != pendingUser || reservation.allocator != user
                || reservation.generation != userGeneration) {
            throw new IllegalStateException("USER reservation is foreign, stale or already resolved");
        }
    }

    private void requireNoPendingUser() {
        if (pendingUser != null) throw new IllegalStateException("A provisional USER reservation is open");
    }

    private void requireContiguousMode() {
        if (user == null) throw new IllegalStateException("PAGING requires a complete resident image, not a contiguous allocation");
    }

    /** Reserva completa ligada al PID antes de publicar el proceso. */
    public UserReservation reserveImage(int pid, int length) {
        if (frames == null) return reserveUser(length);
        requireNoPendingUser();
        if (pid <= 0 || pageTables.containsKey(pid)) throw new IllegalArgumentException("Invalid or already resident image owner");
        new LogicalCodeRange(new LogicalAddress(ProcessControlBlock.LOGICAL_CODE_BASE), length);
        validateProgramLength(length);
        var config = paging.orElseThrow();
        var handles = frames.allocate(config.requiredFrames(length));
        try {
            var entries = new ArrayList<PageTableEntry>();
            for (int i = 0; i < handles.size(); i++) entries.add(new PageTableEntry(i, handles.get(i)));
            var table = new PageTable(UUID.randomUUID(), pid, length, config.pageSize(), entries);
            var reservation = new UserReservation(user, userGeneration, table, () -> {});
            pageTables.put(pid, table);
            pendingUser = reservation;
            return reservation;
        } catch (RuntimeException | Error failure) {
            frames.releaseAll(handles);
            throw failure;
        }
    }

    public void validateImage(ResidentImage image, ProcessControlBlock pcb) {
        Objects.requireNonNull(pcb);
        if (image instanceof MemoryAllocation allocation) {
            validateUserAllocation(allocation, pcb.memoryBounds());
        } else if (image instanceof PageTable table) {
            validatePageTable(table);
            if (!pcb.isPaged() || table.processId() != pcb.processId() || table.size() != pcb.instructionCount()) {
                throw new MemoryProtectionException("Page table does not belong to canonical process");
            }
        } else throw new MemoryProtectionException("Missing resident image");
    }

    private void validatePageTable(PageTable table) {
        if (frames == null || table == null || !table.equals(pageTables.get(table.processId()))) {
            throw new MemoryProtectionException("Page table is stale, foreign or modified");
        }
        try { frames.validateAll(table.frames()); }
        catch (RuntimeException failure) { throw new MemoryProtectionException("Page table contains invalid frame handles"); }
    }

    public PhysicalAddress translateInstructionAddress(ResidentImage image, LogicalCodeRange range, LogicalAddress address) {
        if (image instanceof MemoryAllocation allocation) return translateInstructionAddress(allocation, range, address);
        if (!(image instanceof PageTable table)) throw new MemoryProtectionException("Missing image");
        validatePageTable(table);
        Objects.requireNonNull(range); Objects.requireNonNull(address);
        long offset = (long) address.value() - range.base().value();
        if (range.base().value() != ProcessControlBlock.LOGICAL_CODE_BASE || range.length() != table.size()
                || offset < 0 || offset >= table.size()) throw new MemoryProtectionException("Logical address outside paged executable image");
        var frame = table.entries().get((int) (offset / table.pageSize())).frameHandle().frame();
        return new PhysicalAddress(Math.toIntExact((long) frame.base() + offset % table.pageSize()));
    }

    public Instruction readInstruction(ResidentImage image, int physicalPc) {
        if (image instanceof MemoryAllocation allocation) return readInstruction(allocation, physicalPc);
        if (!(image instanceof PageTable table)) throw new MemoryProtectionException("Missing image");
        validatePageTable(table);
        for (var entry : table.entries()) {
            int base = entry.frameHandle().frame().base();
            long offset = (long) entry.pageNumber() * table.pageSize() + (long) physicalPc - base;
            if (physicalPc >= base && (long) physicalPc < (long) base + table.pageSize() && offset < table.size()) {
                if (positions[physicalPc] instanceof InstructionContent content) return content.instruction();
                throw new MemoryProtectionException("No instruction in mapped frame");
            }
        }
        throw new MemoryProtectionException("Physical PC outside executable page mapping");
    }

    public void writeImage(ResidentImage image, List<? extends Instruction> instructions) {
        if (image instanceof MemoryAllocation allocation) { writeUserBlock(allocation, instructions); return; }
        if (!(image instanceof PageTable table)) throw new MemoryProtectionException("Missing image");
        validatePageTable(table);
        var program = List.copyOf(instructions);
        if (program.size() != table.size()) throw new MemoryProtectionException("Paged image length mismatch");
        var prepared = program.stream().map(InstructionContent::new).toArray(InstructionContent[]::new);
        for (int i = 0; i < prepared.length; i++) {
            int address = pageAddress(table, i);
            regionOf(address);
            if (!positions.getClass().getComponentType().isInstance(prepared[i])) throw new MemoryProtectionException("Incompatible memory cell type");
        }
        for (int i = 0; i < prepared.length; i++) positions[pageAddress(table, i)] = prepared[i];
    }

    private int pageAddress(PageTable table, int offset) {
        return table.entries().get(offset / table.pageSize()).frameHandle().frame().base() + offset % table.pageSize();
    }

    public List<Instruction> readImage(ResidentImage image) {
        if (image instanceof MemoryAllocation allocation) return readUserBlock(allocation);
        if (!(image instanceof PageTable table)) throw new MemoryProtectionException("Missing image");
        validatePageTable(table);
        var result = new ArrayList<Instruction>(table.size());
        for (int i = 0; i < table.size(); i++) {
            if (!(positions[pageAddress(table, i)] instanceof InstructionContent content)) throw new MemoryProtectionException("Incomplete paged image");
            result.add(content.instruction());
        }
        return List.copyOf(result);
    }

    public void release(ResidentImage image) {
        requireNoPendingUser();
        releaseImage(image);
    }

    private void releaseImage(ResidentImage image) {
        if (image instanceof MemoryAllocation allocation) {
            requireContiguousMode();
            user.release(allocation);
            Arrays.fill(positions, allocation.base(), allocation.endExclusive(), EmptyContent.INSTANCE);
        } else if (image instanceof PageTable table) {
            validatePageTable(table);
            frames.releaseAll(table.frames());
            for (var entry : table.entries()) {
                var frame = entry.frameHandle().frame();
                Arrays.fill(positions, frame.base(), frame.base() + frame.capacity(), EmptyContent.INSTANCE);
            }
            pageTables.remove(table.processId());
        } else throw new MemoryProtectionException("Missing image");
    }

    public List<FrameAllocator.Occupancy> frames() { return frames == null ? List.of() : frames.snapshot(); }
    public int unassignableUserCells() { return paging.map(value -> configuration.userPositions() % value.pageSize()).orElse(0); }
    /** Reserva un bloque contiguo bounded exclusivamente en Kernel mediante First-Fit. */
    public MemoryAllocation allocateKernel(int size) { return kernel.allocate(size); }

    /** Valida antes de vaciar; una reserva inválida nunca modifica contenido. */
    public void release(MemoryAllocation allocation) {
        Objects.requireNonNull(allocation, "allocation must not be null");
        if (allocation.region() == MemoryRegion.USER) {
            requireNoPendingUser();
            requireContiguousMode();
        }
        allocator(allocation.region()).release(allocation);
        Arrays.fill(positions, allocation.base(), allocation.endExclusive(), EmptyContent.INSTANCE);
    }

    /**
     * Copia la imagen completa usando el handle USER activo original y rechaza celdas que no contengan
     * instrucciones.
     */
    public List<Instruction> readUserBlock(MemoryAllocation allocation) {
        Objects.requireNonNull(allocation, "allocation must not be null");
        validateWrite(allocation, MemoryRegion.USER, 0, allocation.size());
        var image = new ArrayList<Instruction>(allocation.size());
        for (int address = allocation.base(); address < allocation.endExclusive(); address++) {
            if (!(positions[address] instanceof InstructionContent content)) {
                throw new MemoryProtectionException("USER image contains non-instruction content");
            }
            image.add(content.instruction());
        }
        return List.copyOf(image);
    }

    /** Valida identidad activa y ownership/layout de cada celda antes de sincronizar o liberar. */
    public void validatePcbAllocation(MemoryAllocation allocation, ProcessControlBlock pcb) {
        validatePcbRange(allocation);
        Objects.requireNonNull(pcb);
        for (var field : PcbField.values()) {
            if (!(positions[allocation.base() + field.offset()] instanceof PcbContent cell)
                    || !cell.belongsTo(pcb) || cell.field() != field) {
                throw new MemoryProtectionException("Kernel block does not contain the canonical PCB layout");
            }
        }
    }

    private void validatePcbRange(MemoryAllocation allocation) {
        validateWrite(allocation, MemoryRegion.KERNEL, 0, allocation.size());
        if (allocation.size() != PcbField.PCB_SIZE) {
            throw new MemoryProtectionException("PCB requires exactly " + PcbField.PCB_SIZE + " Kernel cells");
        }
    }

    /** Materializa únicamente sobre una reserva completa vacía; nunca sobrescribe otro propietario. */
    public void materializePcb(MemoryAllocation allocation, ProcessControlBlock pcb) {
        var image = PcbMemoryImage.capture(pcb);
        validatePcbRange(allocation);
        for (int address = allocation.base(); address < allocation.endExclusive(); address++) {
            if (!isEmpty(address)) throw new MemoryProtectionException("PCB allocation is not empty");
        }
        copyPcbImage(allocation, pcb, image);
    }

    /** Prevalida imagen y bloque completo antes de la única copia atómica observable. */
    public void writePcbImage(MemoryAllocation allocation, ProcessControlBlock pcb, PcbMemoryImage image) {
        validatePcbAllocation(allocation, pcb);
        copyPcbImage(allocation, pcb, image);
    }

    private void copyPcbImage(MemoryAllocation allocation, ProcessControlBlock pcb, PcbMemoryImage image) {
        var cells = Objects.requireNonNull(image).cells();
        for (var field : PcbField.values()) {
            var cell = cells.get(field.offset());
            if (!cell.belongsTo(pcb) || cell.field() != field) {
                throw new MemoryProtectionException("PCB image owner/layout mismatch");
            }
        }
        var prepared = cells.toArray(PcbContent[]::new);
        System.arraycopy(prepared, 0, positions, allocation.base(), prepared.length);
    }

    /** Valida handle USER y offset antes de almacenar una instrucción no nula. */
    public void writeInstruction(MemoryAllocation allocation, int offset, Instruction instruction) {
        validateWrite(allocation, MemoryRegion.USER, offset, 1);
        positions[allocation.base() + offset] = new InstructionContent(instruction);
    }

    /** Copia y valida todos los elementos y límites antes de modificar una celda. */
    public void writeUserBlock(MemoryAllocation allocation, List<? extends Instruction> instructions) {
        List<? extends Instruction> program = List.copyOf(Objects.requireNonNull(instructions, "instructions must not be null"));
        validateWrite(allocation, MemoryRegion.USER, 0, program.size());
        var content = program.stream().map(InstructionContent::new).toArray(InstructionContent[]::new);
        System.arraycopy(content, 0, positions, allocation.base(), content.length);
    }

    /**
     * Traduce código lógico mediante un handle USER activo procedente de los recursos canónicos del
     * proceso. No autentica un PID ni concede autorización permanente para accesos posteriores.
     */
    public PhysicalAddress translateInstructionAddress(MemoryAllocation allocation,
            LogicalCodeRange range, LogicalAddress address) {
        Objects.requireNonNull(allocation, "allocation must not be null");
        Objects.requireNonNull(range, "range must not be null");
        Objects.requireNonNull(address, "address must not be null");
        validateWrite(allocation, MemoryRegion.USER, 0, allocation.size());
        if (range.length() != allocation.size()) {
            throw new MemoryProtectionException("Logical code length and USER allocation disagree");
        }
        long offset = (long) address.value() - range.base().value();
        if (offset < 0 || offset >= range.length()) {
            throw new MemoryProtectionException("Logical address is outside executable code");
        }
        long physical = (long) allocation.base() + offset;
        if (physical > Integer.MAX_VALUE || physical < allocation.base()
                || physical >= allocation.endExclusive()
                || physical < configuration.userStartAddress() || physical >= size()) {
            throw new MemoryProtectionException("Translated address is outside USER allocation");
        }
        return new PhysicalAddress((int) physical);
    }

    /**
     * Traduce un índice lógico dentro de Limit a Base más índice y verifica la reserva actual; esta API
     * por bounds no acredita identidad histórica del handle.
     */
    public int physicalAddress(ProcessMemoryBounds bounds, int logicalAddress) {
        requireContiguousMode();
        Objects.requireNonNull(bounds, "bounds must not be null");
        if (logicalAddress < 0 || logicalAddress >= bounds.limit()) {
            throw new MemoryProtectionException("Logical address is outside process bounds: " + logicalAddress);
        }
        if (bounds.base() < configuration.userStartAddress() || bounds.endExclusive() > size()
                || !user.ownsRange(bounds.base(), bounds.limit())) {
            throw new MemoryProtectionException("Process bounds must match an active User allocation");
        }
        return bounds.base() + logicalAddress;
    }

    /** Lee una instrucción con protección Base/Limit y reserva actual; no sustituye la identidad del handle exigida por runtime. */
    public Instruction readInstruction(ProcessMemoryBounds bounds, int logicalAddress) {
        int address = physicalAddress(bounds, logicalAddress);
        if (positions[address] instanceof InstructionContent content) return content.instruction();
        throw new MemoryProtectionException("No instruction at process address: " + logicalAddress);
    }

    /** Valida identidad activa USER y coincidencia de Base/Limit con el PCB sin modificar contenido. */
    public void validateUserAllocation(MemoryAllocation allocation, ProcessMemoryBounds bounds) {
        Objects.requireNonNull(bounds, "bounds must not be null");
        Objects.requireNonNull(allocation, "allocation must not be null");
        validateWrite(allocation, MemoryRegion.USER, 0, allocation.size());
        if (allocation.base() != bounds.base() || allocation.size() != bounds.limit()) {
            throw new MemoryProtectionException("USER allocation and process bounds disagree");
        }
    }

    /** Exige el handle USER activo y un PC dentro del bloque; rechaza el marcador terminal y contenido que no sea InstructionContent. */
    public Instruction readInstruction(MemoryAllocation allocation, int physicalPc) {
        validateWrite(allocation, MemoryRegion.USER, 0, allocation.size());
        if (physicalPc < allocation.base() || physicalPc >= allocation.endExclusive()) {
            throw new MemoryProtectionException("Physical PC outside process allocation: " + physicalPc);
        }
        if (positions[physicalPc] instanceof InstructionContent content) return content.instruction();
        throw new MemoryProtectionException("No instruction at process address: " + physicalPc);
    }

    /** Vacía USER e invalida sus handles; preserva Kernel y su allocator. */
    public void clearUserSpace() {
        if (user != null) user.reset();
        if (frames != null) frames.reset();
        pageTables.clear();
        userGeneration = new Object();
        pendingUser = null;
        Arrays.fill(positions, configuration.userStartAddress(), size(), EmptyContent.INSTANCE);
    }

    /** Vacía ambas regiones y reinicia allocators, invalidando todos los handles anteriores. */
    public void reset() {
        clearUserSpace();
        kernel.reset();
        Arrays.fill(positions, 0, configuration.userStartAddress(), EmptyContent.INSTANCE);
    }

    /** Selecciona el allocator bounded que corresponde a la región solicitada. */
    private MemoryAllocator allocator(MemoryRegion region) {
        return region == MemoryRegion.KERNEL ? kernel : user;
    }

    /**
     * Exige identidad activa y región correcta; rechaza offsets/cantidades fuera de la reserva antes de
     * cualquier escritura.
     */
    private void validateWrite(MemoryAllocation allocation, MemoryRegion region, int offset, int count) {
        Objects.requireNonNull(allocation, "allocation must not be null");
        if (allocation.region() != region || allocator(region) == null || !allocator(region).isActive(allocation)) {
            throw new MemoryProtectionException("Write requires an active allocation in " + region);
        }
        if (offset < 0 || count < 0 || offset > allocation.size() || count > allocation.size() - offset) {
            throw new InvalidMemoryAddressException("Write exceeds allocation bounds");
        }
    }
}
