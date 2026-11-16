package io.github.rajami1205.osimulator.application.statistics;

import io.github.rajami1205.osimulator.application.process.CompletedProcessRecord;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.LogicalProcessTimes;
import io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Calcula las estadísticas de una ejecución a partir de los procesos terminados y del Gantt. Función pura:
 * no consulta reloj, runtime ni políticas.
 *
 * <p>Convención de clase: Tr = Finish − Arrival, Tr/Ts con Ts = Burst declarado, Wait = Tr − instrucciones
 * ejecutadas, Response = Start − Arrival. Media global de Tr/Ts = suma de ratios / cantidad de procesos
 * (no la media de las medias por CPU). Utilización global = ocupado / (CPUs · transcurrido).
 */
public final class StatisticsCalculator {
    private StatisticsCalculator() { }

    /** La ejecución empieza siempre en el origen del reloj lógico. */
    public static final long RUN_START = 0;

    public static RunStatistics compute(SchedulingPolicyId policy, long end, boolean complete,
            List<CompletedProcessRecord> completed, List<CpuTimelineView> timeline) {
        Objects.requireNonNull(policy, "policy must not be null");
        Objects.requireNonNull(completed, "completed must not be null");
        Objects.requireNonNull(timeline, "timeline must not be null");
        if (timeline.isEmpty()) {
            throw new IllegalArgumentException("Timeline must include every CPU");
        }
        long elapsed = end - RUN_START;

        List<ProcessStatistics> processes = completed.stream()
                .map(StatisticsCalculator::processStatistics)
                .sorted(Comparator.comparingInt(ProcessStatistics::processId))
                .toList();

        List<CpuStatistics> cpus = new ArrayList<>();
        long totalBusy = 0;
        long totalIdle = 0;
        for (CpuTimelineView cpuTimeline : timeline) {
            CpuStatistics cpuStatistics = cpuStatistics(cpuTimeline, elapsed, processes);
            cpus.add(cpuStatistics);
            totalBusy += cpuStatistics.busyTicks();
            totalIdle += cpuStatistics.idleTicks();
        }

        int cpuCount = timeline.size();
        double utilization = ratio(totalBusy, (double) cpuCount * elapsed);
        return new RunStatistics(0, policy, cpuCount, RUN_START, end, complete, processes, cpus, totalIdle,
                mean(processes, ProcessStatistics::serviceTime),
                mean(processes, ProcessStatistics::turnaround),
                mean(processes, ProcessStatistics::normalizedTurnaround),
                mean(processes, ProcessStatistics::response),
                mean(processes, ProcessStatistics::waitingTime),
                utilization, timeline);
    }

    private static ProcessStatistics processStatistics(CompletedProcessRecord record) {
        LogicalProcessTimes times = record.logicalTimes();
        long arrival = times.arrivalTime();
        long start = times.firstDispatchTime().orElseThrow(
                () -> new IllegalStateException("Completed process without first dispatch: " + record.processId()));
        long finish = times.finishTime().orElseThrow(
                () -> new IllegalStateException("Completed process without finish: " + record.processId()));
        int serviceTime = record.declaredBurst();
        long executed = record.accounting().cpuTicks();

        long turnaround = finish - arrival;
        double normalizedTurnaround = (double) turnaround / serviceTime;
        long response = start - arrival;
        long wait = turnaround - executed;
        return new ProcessStatistics(record.processId(), record.cpuAffinity(), arrival, serviceTime, start, finish,
                turnaround, normalizedTurnaround, response, wait, executed);
    }

    private static CpuStatistics cpuStatistics(CpuTimelineView cpuTimeline, long elapsed,
            List<ProcessStatistics> allProcesses) {
        CpuId cpu = cpuTimeline.cpu();
        List<ProcessStatistics> processes = allProcesses.stream()
                .filter(process -> process.cpu().equals(cpu))
                .toList();
        long busy = cpuTimeline.busyTicks();
        long idle = Math.max(elapsed - busy, 0);
        double utilization = ratio(busy, elapsed);
        return new CpuStatistics(cpu, busy, idle, utilization, processes.size(),
                mean(processes, ProcessStatistics::turnaround),
                mean(processes, ProcessStatistics::normalizedTurnaround),
                mean(processes, ProcessStatistics::response),
                mean(processes, ProcessStatistics::waitingTime));
    }

    private static double mean(List<ProcessStatistics> processes, ToDoubleFunction<ProcessStatistics> value) {
        return processes.stream().mapToDouble(value).average().orElse(0.0);
    }

    private static double ratio(double numerator, double denominator) {
        if (denominator <= 0) {
            return 0.0;
        }
        return numerator / denominator;
    }
}
