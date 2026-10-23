package io.github.rajami1205.osimulator.model.configuration;

import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import java.util.Objects;
import io.github.rajami1205.osimulator.model.memory.PcbField;
import io.github.rajami1205.osimulator.model.process.ProcessTable;

/** Configuración inmutable de la máquina; las capacidades no crean subsistemas. */
public record SimulatorConfiguration(
        MemoryConfiguration mainMemory,
        int secondaryStoragePositions,
        int virtualMemoryPositions
) {
    public static final int MIN_MAIN_MEMORY = 256;
    public static final int MIN_KERNEL_MEMORY = PcbField.PCB_SIZE * ProcessTable.MAX_ADMITTED_PROCESSES;

    /**
     * Exige Main >= 256 y Kernel >= 140, además de Secondary >= 128 y reserva Virtual >= 64 e inferior al
     * total.
     */
    public SimulatorConfiguration {
        Objects.requireNonNull(mainMemory, "mainMemory must not be null");
        if (mainMemory.totalPositions() < MIN_MAIN_MEMORY) {
            throw new IllegalArgumentException("Main Memory must be at least " + MIN_MAIN_MEMORY);
        }
        if (mainMemory.kernelReservedPositions() < MIN_KERNEL_MEMORY) {
            throw new IllegalArgumentException("Kernel Memory must be at least " + MIN_KERNEL_MEMORY);
        }
        if (secondaryStoragePositions < 128) {
            throw new IllegalArgumentException("Secondary Storage must be at least 128");
        }
        if (virtualMemoryPositions < 64 || virtualMemoryPositions >= secondaryStoragePositions) {
            throw new IllegalArgumentException("Virtual Memory must be at least 64 and less than Secondary Storage");
        }
    }

    /**
     * Defaults 256/140/512/64. Kernel garantiza cinco bloques PCB de 28 celdas;
     * los 116 espacios USER siguen limitando los programas que pueden residir simultáneamente.
     */
    public static SimulatorConfiguration defaults() {
        return new SimulatorConfiguration(new MemoryConfiguration(MIN_MAIN_MEMORY, MIN_KERNEL_MEMORY), 512, 64);
    }
}
