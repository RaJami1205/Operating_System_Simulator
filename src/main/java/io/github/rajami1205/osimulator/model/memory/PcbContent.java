package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import java.util.Objects;

/** Referencia al PCB canónico; su dirección simulada pertenece a la celda. */
public record PcbContent(ProcessControlBlock pcb) implements MemoryContent {
    public PcbContent {
        Objects.requireNonNull(pcb, "pcb must not be null");
    }
}
