package io.github.rajami1205.osimulator.application.statistics;

import io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId;
import java.util.List;
import java.util.Objects;

/**
 * Estadísticas inmutables de una ejecución.
 *
 * @param runNumber    1, 2, 3… para las ejecuciones terminadas de la app; 0 para la ejecución en curso
 * @param start        inicio de la ejecución (origen del reloj lógico)
 * @param end          segundo lógico actual, o final si la ejecución terminó
 * @param complete     true si la ejecución llegó a FINISHED (si no, sólo cuenta los procesos ya terminados)
 * @param totalIdle    suma de los ticks ociosos de todas las CPUs
 * @param meanNormalizedTurnaround suma de los Tr/Ts de cada proceso / cantidad de procesos
 * @param utilization  suma de ticks ocupados / (CPUs · transcurrido)
 */
public record RunStatistics(int runNumber, SchedulingPolicyId policy, int cpuCount, long start, long end,
        boolean complete, List<ProcessStatistics> processes, List<CpuStatistics> cpus, long totalIdle,
        double meanServiceTime, double meanTurnaround, double meanNormalizedTurnaround, double meanResponse,
        double meanWait, double utilization, List<CpuTimelineView> timeline) {
    public RunStatistics {
        Objects.requireNonNull(policy, "policy must not be null");
        processes = List.copyOf(processes);
        cpus = List.copyOf(cpus);
        timeline = List.copyOf(timeline);
        if (runNumber < 0 || cpuCount <= 0 || end < start || cpus.size() != cpuCount) {
            throw new IllegalArgumentException("Invalid run statistics");
        }
    }

    /** Tiempo transcurrido de la ejecución. */
    public long elapsed() {
        return end - start;
    }

    /** Copia numerada para el historial acumulado. */
    public RunStatistics withRunNumber(int number) {
        return new RunStatistics(number, policy, cpuCount, start, end, complete, processes, cpus, totalIdle,
                meanServiceTime, meanTurnaround, meanNormalizedTurnaround, meanResponse, meanWait, utilization,
                timeline);
    }
}
