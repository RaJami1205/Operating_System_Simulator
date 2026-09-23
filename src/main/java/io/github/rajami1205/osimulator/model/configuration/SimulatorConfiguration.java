package io.github.rajami1205.osimulator.model.configuration;

import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import java.util.Objects;

/** Configuración inmutable de la máquina; las capacidades no crean subsistemas. */
public record SimulatorConfiguration(
        MemoryConfiguration mainMemory,
        int secondaryStoragePositions,
        int virtualMemoryPositions
) {
    public SimulatorConfiguration {
        Objects.requireNonNull(mainMemory, "mainMemory must not be null");
        if (secondaryStoragePositions <= 0) {
            throw new IllegalArgumentException("Secondary Storage must be greater than zero");
        }
        if (virtualMemoryPositions <= 0 || virtualMemoryPositions >= secondaryStoragePositions) {
            throw new IllegalArgumentException("Virtual Memory must be positive and less than Secondary Storage");
        }
    }

    public static SimulatorConfiguration defaults() {
        return new SimulatorConfiguration(new MemoryConfiguration(256, 32), 512, 64);
    }
}
