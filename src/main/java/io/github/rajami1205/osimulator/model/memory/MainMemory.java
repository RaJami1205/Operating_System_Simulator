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

/**
 * Memoria física tipada con allocators First-Fit independientes para Kernel y USER. La disponibilidad
 * pertenece a los allocators, no a EmptyContent; el fetch usa el handle canónico.
 */
public final class MainMemory {
    private final MemoryConfiguration configuration;
    private final MemoryContent[] positions;
    private final MemoryAllocator kernel;
    private final MemoryAllocator user;

    /** Crea celdas vacías y dos allocators bounded independientes según MemoryConfiguration. */
    public MainMemory(MemoryConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        positions = new MemoryContent[configuration.totalPositions()];
        kernel = new FirstFitMemoryAllocator(MemoryRegion.KERNEL, 0, configuration.kernelReservedPositions());
        user = new FirstFitMemoryAllocator(MemoryRegion.USER, configuration.userStartAddress(), configuration.userPositions());
        Arrays.fill(positions, EmptyContent.INSTANCE);
    }

    /** Expone la configuración inmutable que separa Kernel y USER. */
    public MemoryConfiguration configuration() { return configuration; }
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
    /** Reserva un bloque contiguo bounded exclusivamente en USER mediante First-Fit. */
    public MemoryAllocation allocateUser(int size) { return user.allocate(size); }
    /** Reserva un bloque contiguo bounded exclusivamente en Kernel mediante First-Fit. */
    public MemoryAllocation allocateKernel(int size) { return kernel.allocate(size); }

    /** Valida antes de vaciar; una reserva inválida nunca modifica contenido. */
    public void release(MemoryAllocation allocation) {
        Objects.requireNonNull(allocation, "allocation must not be null");
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

    /**
     * Exige la reserva Kernel original de una posición y que contenga exactamente el PCB canónico
     * indicado.
     */
    public void validatePcbAllocation(MemoryAllocation allocation, ProcessControlBlock pcb) {
        validateWrite(allocation, MemoryRegion.KERNEL, 0, 1);
        if (allocation.size() != 1 || !(positions[allocation.base()] instanceof PcbContent content)
                || content.pcb() != pcb) {
            throw new MemoryProtectionException("Kernel allocation does not contain the canonical PCB");
        }
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

    /** Valida handle Kernel y offset antes de conservar la referencia al PCB canónico. */
    public void writePcb(MemoryAllocation allocation, int offset, ProcessControlBlock pcb) {
        validateWrite(allocation, MemoryRegion.KERNEL, offset, 1);
        positions[allocation.base() + offset] = new PcbContent(pcb);
    }

    /**
     * Traduce un índice lógico dentro de Limit a Base más índice y verifica la reserva actual; esta API
     * por bounds no acredita identidad histórica del handle.
     */
    public int physicalAddress(ProcessMemoryBounds bounds, int logicalAddress) {
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
    public Instruction readInstruction(MemoryAllocation allocation, int logicalPc) {
        validateWrite(allocation, MemoryRegion.USER, logicalPc, 1);
        if (positions[allocation.base() + logicalPc] instanceof InstructionContent content) return content.instruction();
        throw new MemoryProtectionException("No instruction at process address: " + logicalPc);
    }

    /** Vacía USER e invalida sus handles; preserva Kernel y su allocator. */
    public void clearUserSpace() {
        Arrays.fill(positions, configuration.userStartAddress(), size(), EmptyContent.INSTANCE);
        user.reset();
    }

    /** Vacía ambas regiones y reinicia allocators, invalidando todos los handles anteriores. */
    public void reset() {
        Arrays.fill(positions, EmptyContent.INSTANCE);
        kernel.reset();
        user.reset();
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
        if (allocation.region() != region || !allocator(region).isActive(allocation)) {
            throw new MemoryProtectionException("Write requires an active allocation in " + region);
        }
        if (offset < 0 || count < 0 || offset > allocation.size() || count > allocation.size() - offset) {
            throw new InvalidMemoryAddressException("Write exceeds allocation bounds");
        }
    }
}
