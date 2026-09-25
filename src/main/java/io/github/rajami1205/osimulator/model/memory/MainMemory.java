package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryAddressException;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessMemoryBounds;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Memoria física tipada con reservas Kernel/User y acceso lógico protegido. */
public final class MainMemory {
    private final MemoryConfiguration configuration;
    private final MemoryContent[] positions;
    private final MemoryAllocator kernel;
    private final MemoryAllocator user;

    public MainMemory(MemoryConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        positions = new MemoryContent[configuration.totalPositions()];
        kernel = new FirstFitMemoryAllocator(MemoryRegion.KERNEL, 0, configuration.kernelReservedPositions());
        user = new FirstFitMemoryAllocator(MemoryRegion.USER, configuration.userStartAddress(), configuration.userPositions());
        Arrays.fill(positions, EmptyContent.INSTANCE);
    }

    public MemoryConfiguration configuration() { return configuration; }
    public int size() { return positions.length; }
    public MemoryRegion regionOf(int address) { return configuration.regionOf(address); }

    public MemoryContent read(int address) {
        regionOf(address);
        return positions[address];
    }

    public MemoryCell cell(int address) {
        return new MemoryCell(address, regionOf(address), read(address));
    }

    public boolean isEmpty(int address) { return read(address) == EmptyContent.INSTANCE; }
    public MemoryAllocation allocateUser(int size) { return user.allocate(size); }
    public MemoryAllocation allocateKernel(int size) { return kernel.allocate(size); }

    /** Valida antes de vaciar; una reserva inválida nunca modifica contenido. */
    public void release(MemoryAllocation allocation) {
        Objects.requireNonNull(allocation, "allocation must not be null");
        allocator(allocation.region()).release(allocation);
        Arrays.fill(positions, allocation.base(), allocation.endExclusive(), EmptyContent.INSTANCE);
    }

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

    public void writePcb(MemoryAllocation allocation, int offset, ProcessControlBlock pcb) {
        validateWrite(allocation, MemoryRegion.KERNEL, offset, 1);
        positions[allocation.base() + offset] = new PcbContent(pcb);
    }

    /** Limit es una cantidad; el marcador terminal nunca es una dirección de fetch. */
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

    public Instruction readInstruction(ProcessMemoryBounds bounds, int logicalAddress) {
        int address = physicalAddress(bounds, logicalAddress);
        if (positions[address] instanceof InstructionContent content) return content.instruction();
        throw new MemoryProtectionException("No instruction at process address: " + logicalAddress);
    }

    public void clearUserSpace() {
        Arrays.fill(positions, configuration.userStartAddress(), size(), EmptyContent.INSTANCE);
        user.reset();
    }

    public void reset() {
        Arrays.fill(positions, EmptyContent.INSTANCE);
        kernel.reset();
        user.reset();
    }

    private MemoryAllocator allocator(MemoryRegion region) {
        return region == MemoryRegion.KERNEL ? kernel : user;
    }

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
