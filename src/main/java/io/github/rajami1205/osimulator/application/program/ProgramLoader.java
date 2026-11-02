package io.github.rajami1205.osimulator.application.program;

import io.github.rajami1205.osimulator.application.program.exception.ProgramLoadException;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.memory.MemoryAllocation;
import io.github.rajami1205.osimulator.model.memory.MemoryMode;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryAllocationException;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.LogicalProcessTimes;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessConfigurationException;
import java.util.List;
import java.util.Objects;

/**
 * Coordina la carga de un programa semántico en la memoria User simulada.
 */
public final class ProgramLoader {

    /** Valida el programa y lo carga en User Memory con un PCB en READY. */
    public ProcessControlBlock load(
            MainMemory memory,
            int processId,
            List<Instruction> instructions
    ) {
        return loadImage(memory, processId, instructions, LogicalProcessTimes.admitted(0, 0), CpuId.ZERO).pcb();
    }

    /**
     * Reserva USER, escribe el bloque completo y crea un PCB READY con PC lógico inicial; ante fallo
     * posterior libera la reserva. No almacena el PCB en Kernel.
     */
    public ProgramLoadResult loadWithAllocation(
            MainMemory memory, int processId, List<Instruction> instructions
    ) {
        return loadWithAllocation(memory, processId, instructions, LogicalProcessTimes.admitted(0, 0));
    }

    public ProgramLoadResult loadWithAllocation(MainMemory memory, int processId,
            List<Instruction> instructions, LogicalProcessTimes logicalTimes) {
        return loadWithAllocation(memory, processId, instructions, logicalTimes, CpuId.ZERO);
    }

    public ProgramLoadResult loadWithAllocation(MainMemory memory, int processId,
            List<Instruction> instructions, LogicalProcessTimes logicalTimes, CpuId cpuAffinity) {
        var provisional = prepareWithAllocation(memory, processId, instructions, logicalTimes, cpuAffinity);
        memory.confirmUser(provisional.reservation());
        return provisional.loaded();
    }

    /** Admission conserva la reserva abierta hasta publicar Job, PCB, recursos y cola. */
    public PreparedProgramLoad prepareWithAllocation(MainMemory memory, int processId,
            List<Instruction> instructions, LogicalProcessTimes logicalTimes, CpuId cpuAffinity) {
        if (Objects.requireNonNull(memory).memoryMode() == MemoryMode.PAGING) {
            throw new ProgramLoadException("Use image loading for PAGING; no contiguous allocation exists");
        }
        var prepared = prepareImage(memory, processId, instructions, logicalTimes, cpuAffinity);
        return new PreparedProgramLoad(new ProgramLoadResult(prepared.loaded().pcb(),
                prepared.reservation().allocation()), prepared.reservation());
    }

    public ResidentProgramLoad loadImage(MainMemory memory, int processId, List<Instruction> instructions,
            LogicalProcessTimes logicalTimes, CpuId cpuAffinity) {
        var prepared = prepareImage(memory, processId, instructions, logicalTimes, cpuAffinity);
        memory.confirmUser(prepared.reservation());
        return prepared.loaded();
    }

    public PreparedResidentProgramLoad prepareImage(MainMemory memory, int processId,
            List<Instruction> instructions, LogicalProcessTimes logicalTimes, CpuId cpuAffinity) {
        return prepareImage(memory, null, processId, instructions, logicalTimes, cpuAffinity);
    }

    public PreparedResidentProgramLoad prepareImage(MainMemory memory,
            io.github.rajami1205.osimulator.model.storage.SecondaryStorage storage, int processId,
            List<Instruction> instructions, LogicalProcessTimes logicalTimes, CpuId cpuAffinity) {
        Objects.requireNonNull(cpuAffinity);
        Objects.requireNonNull(logicalTimes);
        MainMemory nonNullMemory = Objects.requireNonNull(
                memory,
                "memory must not be null"
        );
        List<Instruction> program = List.copyOf(
                Objects.requireNonNull(instructions, "instructions must not be null")
        );

        if (program.isEmpty()) {
            throw new ProgramLoadException("Program must contain at least one instruction");
        }

        io.github.rajami1205.osimulator.model.storage.StorageAllocation backingAllocation = null;
        if (memory.isDemandPaging()) {
            Objects.requireNonNull(storage, "DEMAND loading requires canonical backing storage");
            if (program.size() > storage.virtualMemoryPositions()) throw new io.github.rajami1205.osimulator.model.memory.exception.IncompatibleProgramException("Program exceeds backing capacity");
            if (processId <= 0) throw new IllegalArgumentException("Invalid PID");
            memory.validateProgramLength(program.size());
            backingAllocation = storage.allocateSwap(program.size());
            try { storage.writeSwapBlock(backingAllocation, program); storage.readSwapBlock(backingAllocation); }
            catch (RuntimeException | Error failure) {
                try { storage.releaseSwap(backingAllocation); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }

        MainMemory.UserReservation reservation;
        try {
            reservation = nonNullMemory.reserveImage(processId, program.size());
        } catch (MemoryAllocationException exception) {
            if (backingAllocation != null) storage.releaseSwap(backingAllocation);
            throw new ProgramLoadException("Unable to allocate User image", exception);
        } catch (RuntimeException | Error failure) {
            if (backingAllocation != null) {
                try { storage.releaseSwap(backingAllocation); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
        try {
            var image = reservation.image();
            if (!memory.isDemandPaging()) nonNullMemory.writeImage(image, program);
            ProcessControlBlock pcb = image instanceof MemoryAllocation allocation
                    ? createProcessControlBlock(processId, allocation.base(), allocation.size(), logicalTimes, cpuAffinity)
                    : ProcessControlBlock.paged(processId, image.size(), logicalTimes, cpuAffinity);
            pcb.changeState(ProcessState.READY);
            var backing = backingAllocation == null ? java.util.Optional.<io.github.rajami1205.osimulator.model.storage.PageBacking>empty()
                    : java.util.Optional.of(new io.github.rajami1205.osimulator.model.storage.PageBacking(
                            ((io.github.rajami1205.osimulator.model.memory.PageTable) image).imageId(), processId,
                            image.size(), ((io.github.rajami1205.osimulator.model.memory.PageTable) image).pageSize(), backingAllocation));
            return new PreparedResidentProgramLoad(new ResidentProgramLoad(pcb, image), reservation, backing);
        } catch (RuntimeException | Error exception) {
            try {
                nonNullMemory.cancelUser(reservation);
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != exception) exception.addSuppressed(cleanup);
            }
            if (backingAllocation != null) {
                try { storage.releaseSwap(backingAllocation); } catch (RuntimeException | Error cleanup) { exception.addSuppressed(cleanup); }
            }
            throw exception;
        }
    }

    /** Crea los metadatos del proceso y traduce errores de configuración a errores de carga. */
    private ProcessControlBlock createProcessControlBlock(
            int processId,
            int programStartAddress,
            int instructionCount, LogicalProcessTimes logicalTimes, CpuId cpuAffinity
    ) {
        try {
            return new ProcessControlBlock(
                    processId,
                    programStartAddress,
                    instructionCount, logicalTimes, cpuAffinity
            );
        } catch (InvalidProcessConfigurationException exception) {
            throw new ProgramLoadException(
                    "Unable to create Process Control Block for program load",
                    exception
            );
        }
    }

}
