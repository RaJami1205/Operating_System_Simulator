package io.github.rajami1205.osimulator.model.configuration;

import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import java.util.Objects;
import io.github.rajami1205.osimulator.model.memory.PcbField;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId;

/** Configuración inmutable de la máquina; las capacidades no crean subsistemas. */
public record SimulatorConfiguration(
        MemoryConfiguration mainMemory,
        int secondaryStoragePositions,
        int virtualMemoryPositions,
        int cpuCount,
        SchedulingPolicyId schedulingPolicy
) {
    public static final int MIN_CPU_COUNT = 1;
    public static final int MAX_CPU_COUNT = 5;
    public static final int PROCESSES_PER_CPU = ProcessTable.MAX_ADMITTED_PROCESSES;
    public static final int MIN_MAIN_MEMORY = 256;
    /** Mínimo compatible de una CPU para los controles actuales. */
    public static final int MIN_KERNEL_MEMORY = PcbField.PCB_SIZE * ProcessTable.MAX_ADMITTED_PROCESSES;

    /** Conserva la configuración histórica de una CPU. */
    public SimulatorConfiguration(MemoryConfiguration mainMemory, int secondaryStoragePositions,
            int virtualMemoryPositions) {
        this(mainMemory, secondaryStoragePositions, virtualMemoryPositions, MIN_CPU_COUNT);
    }

    /** Compatibilidad de capacidad configurable con FCFS predeterminado. */
    public SimulatorConfiguration(MemoryConfiguration mainMemory, int secondaryStoragePositions,
            int virtualMemoryPositions, int cpuCount) {
        this(mainMemory, secondaryStoragePositions, virtualMemoryPositions, cpuCount, SchedulingPolicyId.FCFS);
    }

    /**
     * Exige Main >= 256 y Kernel suficiente para cinco PCBs por CPU, además de Secondary >= 128 y reserva Virtual >= 64 e inferior al
     * total.
     */
    public SimulatorConfiguration {
        Objects.requireNonNull(schedulingPolicy, "schedulingPolicy must not be null");
        Objects.requireNonNull(mainMemory, "mainMemory must not be null");
        if (cpuCount < MIN_CPU_COUNT || cpuCount > MAX_CPU_COUNT) {
            throw new IllegalArgumentException("CPU count must be between 1 and 5");
        }
        if (mainMemory.totalPositions() < MIN_MAIN_MEMORY) {
            throw new IllegalArgumentException("Main Memory must be at least " + MIN_MAIN_MEMORY);
        }
        int requiredKernel = PcbField.PCB_SIZE * PROCESSES_PER_CPU * cpuCount;
        if (mainMemory.kernelReservedPositions() < requiredKernel) {
            throw new IllegalArgumentException("Kernel Memory must be at least " + requiredKernel);
        }
        if (secondaryStoragePositions < 128) {
            throw new IllegalArgumentException("Secondary Storage must be at least 128");
        }
        if (virtualMemoryPositions < 64 || virtualMemoryPositions >= secondaryStoragePositions) {
            throw new IllegalArgumentException("Virtual Memory must be at least 64 and less than Secondary Storage");
        }
    }

    /** Cupo de admisión; no garantiza residencia de las imágenes USER ni ejecución multi-CPU. */
    public int maxProcesses() { return PROCESSES_PER_CPU * cpuCount; }

    /** Celdas necesarias para los bloques PCB de la capacidad configurada. */
    public int minimumKernelMemory() { return PcbField.PCB_SIZE * maxProcesses(); }

    /**
     * Defaults 256/140/512/64. Kernel garantiza cinco bloques PCB de 28 celdas;
     * los 116 espacios USER siguen limitando los programas que pueden residir simultáneamente.
     */
    public static SimulatorConfiguration defaults() {
        return new SimulatorConfiguration(new MemoryConfiguration(MIN_MAIN_MEMORY, MIN_KERNEL_MEMORY), 512, 64);
    }
}
