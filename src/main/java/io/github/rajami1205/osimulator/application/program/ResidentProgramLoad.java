package io.github.rajami1205.osimulator.application.program;

import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import java.util.Objects;

public record ResidentProgramLoad(ProcessControlBlock pcb, ResidentImage image) {
    public ResidentProgramLoad {
        Objects.requireNonNull(pcb); Objects.requireNonNull(image);
        if (image.size() != pcb.instructionCount()) throw new IllegalArgumentException("Image length mismatch");
        switch (image) {
            case MemoryAllocation allocation -> new ProgramLoadResult(pcb, allocation);
            case PageTable table -> {
                if (!pcb.isPaged() || table.processId() != pcb.processId()) throw new IllegalArgumentException("Page table owner mismatch");
            }
        }
    }
}
