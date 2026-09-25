package io.github.rajami1205.osimulator.application.program;

import io.github.rajami1205.osimulator.model.memory.MemoryAllocation;
import io.github.rajami1205.osimulator.model.memory.MemoryRegion;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import java.util.Objects;

/** PCB canónico y reserva USER original; el receptor asume ownership de la reserva. */
public record ProgramLoadResult(ProcessControlBlock pcb, MemoryAllocation userAllocation) {
    public ProgramLoadResult {
        Objects.requireNonNull(pcb, "pcb must not be null");
        Objects.requireNonNull(userAllocation, "userAllocation must not be null");
        if (userAllocation.region() != MemoryRegion.USER
                || userAllocation.base() != pcb.memoryBounds().base()
                || userAllocation.size() != pcb.memoryBounds().limit()) {
            throw new IllegalArgumentException("USER allocation must match PCB bounds");
        }
    }
}
