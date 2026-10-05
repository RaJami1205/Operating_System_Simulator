package io.github.rajami1205.osimulator.model.configuration;

import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import java.util.Objects;

/** Configuración inmutable de la máquina; las capacidades no crean subsistemas. */
public record SimulatorConfiguration(
        MemoryConfiguration mainMemory,
        int secondaryStoragePositions,
        int virtualMemoryPositions
) {
    /**
     * Exige MainMemory válida, Secondary de al menos 128 y reserva Virtual de al menos 64 e inferior al
     * total.
     */
    public SimulatorConfiguration {
        Objects.requireNonNull(mainMemory, "mainMemory must not be null");
        if (secondaryStoragePositions < 128) {
            throw new IllegalArgumentException("Secondary Storage must be at least 128");
        }
        if (virtualMemoryPositions < 64 || virtualMemoryPositions >= secondaryStoragePositions) {
            throw new IllegalArgumentException("Virtual Memory must be at least 64 and less than Secondary Storage");
        }
    }

    /** Devuelve capacidades aprobadas Main/Kernel/Secondary/Virtual de 256/32/512/64. */
    public static SimulatorConfiguration defaults() {
        return new SimulatorConfiguration(new MemoryConfiguration(256, 32), 512, 64);
    }
}
