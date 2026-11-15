package io.github.rajami1205.osimulator.application.statistics;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import java.util.Objects;

/**
 * Resumen de una CPU: ticks ocupados y ociosos, utilización (ocupado / transcurrido) y promedios de los
 * procesos que terminó.
 */
public record CpuStatistics(CpuId cpu, long busyTicks, long idleTicks, double utilization, int completedProcesses,
        double meanTurnaround, double meanNormalizedTurnaround, double meanResponse, double meanWait) {
    public CpuStatistics {
        Objects.requireNonNull(cpu, "cpu must not be null");
        if (busyTicks < 0 || idleTicks < 0) {
            throw new IllegalArgumentException("Ticks must not be negative");
        }
    }
}
