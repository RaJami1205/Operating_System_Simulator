package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import java.util.Objects;

/** Publica exclusivamente PCB canónico → imagen capturada → bloque Kernel canónico. */
public final class PcbKernelSynchronizer {
    private final MainMemory memory;
    private final ProcessResourceRegistry resources;

    public PcbKernelSynchronizer(MainMemory memory, ProcessResourceRegistry resources) {
        this.memory = Objects.requireNonNull(memory);
        this.resources = Objects.requireNonNull(resources);
    }

    public void validate(ProcessControlBlock pcb) {
        memory.validatePcbAllocation(resource(pcb).kernel(), pcb);
    }

    public void sync(ProcessControlBlock pcb) {
        var resource = resource(pcb);
        var image = PcbMemoryImage.capture(pcb, pcb.nextPcbAddress(), resource.residence() instanceof UserImageResidence.Resident);
        memory.writePcbImage(resource.kernel(), pcb, image);
    }

    private ProcessResources resource(ProcessControlBlock pcb) {
        return resources.find(pcb.processId()).orElseThrow(() -> new IllegalStateException("Missing PCB resources"));
    }
}
