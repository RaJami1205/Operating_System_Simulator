package io.github.rajami1205.osimulator.presentation;

import io.github.rajami1205.osimulator.application.statistics.CpuStatistics;
import io.github.rajami1205.osimulator.application.statistics.CpuTimelineView;
import io.github.rajami1205.osimulator.application.statistics.ProcessStatistics;
import io.github.rajami1205.osimulator.application.statistics.RunStatistics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Formatea las estadísticas inmutables del snapshot para la pestaña Statistics. No calcula nada: sólo
 * convierte valores ya calculados en texto (2 decimales para ratios y promedios).
 */
final class StatisticsText {
    private StatisticsText() { }

    static final String AVERAGE_LABEL = "Average";

    /** Una fila de la tabla por proceso (o la fila de promedios). */
    record ProcessRow(String pid, String cpu, String arrival, String serviceTime, String start, String finish,
            String turnaround, String normalizedTurnaround, String response, String waitingTime,
            String executed) { }

    /** Una fila del historial acumulado de ejecuciones terminadas. */
    record HistoryRow(String run, String algorithm, String cpus, String start, String end, String idle,
            String meanTurnaround, String meanNormalizedTurnaround, String utilization) { }

    static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    static String percent(double ratio) {
        return String.format(Locale.ROOT, "%.1f%%", ratio * 100);
    }

    /** Filas por PID más la fila final "Average" de promedios (como la tabla 9.5 de Stallings). */
    static List<ProcessRow> processRows(RunStatistics statistics) {
        List<ProcessRow> rows = new ArrayList<>();
        for (ProcessStatistics process : statistics.processes()) {
            rows.add(new ProcessRow(
                    Integer.toString(process.processId()),
                    Integer.toString(process.cpu().value()),
                    Long.toString(process.arrival()),
                    Integer.toString(process.serviceTime()),
                    Long.toString(process.start()),
                    Long.toString(process.finish()),
                    Long.toString(process.turnaround()),
                    decimal(process.normalizedTurnaround()),
                    Long.toString(process.response()),
                    Long.toString(process.waitingTime()),
                    Long.toString(process.executedInstructions())));
        }
        if (!statistics.processes().isEmpty()) {
            rows.add(new ProcessRow(AVERAGE_LABEL, "", "",
                    decimal(statistics.meanServiceTime()), "", "",
                    decimal(statistics.meanTurnaround()),
                    decimal(statistics.meanNormalizedTurnaround()),
                    decimal(statistics.meanResponse()),
                    decimal(statistics.meanWait()), ""));
        }
        return List.copyOf(rows);
    }

    static List<HistoryRow> historyRows(List<RunStatistics> history) {
        return history.stream().map(run -> new HistoryRow(
                "#" + run.runNumber(),
                run.policy().name(),
                Integer.toString(run.cpuCount()),
                Long.toString(run.start()),
                Long.toString(run.end()),
                Long.toString(run.totalIdle()),
                decimal(run.meanTurnaround()),
                decimal(run.meanNormalizedTurnaround()),
                percent(run.utilization()))).toList();
    }

    /** Etiqueta del selector de ejecución. */
    static String runLabel(RunStatistics run) {
        String cpuText = run.cpuCount() == 1 ? "1 CPU" : run.cpuCount() + " CPUs";
        return "Run #" + run.runNumber() + " — " + run.policy().name() + ", " + cpuText;
    }

    /** Gantt de una CPU: "CPU 0 | 1 1 2 - 3" (PID por tick, '-' ociosa). */
    static String ganttLine(CpuTimelineView timeline) {
        String slots = timeline.slots().stream()
                .map(slot -> slot.map(Object::toString).orElse("-"))
                .collect(Collectors.joining(" "));
        return "CPU " + timeline.cpu().value() + " | " + slots;
    }

    /** Resumen: encabezado de la ejecución, resumen por CPU y Gantt. */
    static String summary(RunStatistics statistics) {
        StringBuilder text = new StringBuilder();
        String state = statistics.complete() ? "finished" : "in progress (only completed processes)";
        String title = statistics.runNumber() > 0 ? "Run #" + statistics.runNumber() : "Current run";
        text.append(title).append(" — ").append(state)
                .append("\nAlgorithm: ").append(statistics.policy().name())
                .append("   CPUs: ").append(statistics.cpuCount())
                .append("\nStart: ").append(statistics.start())
                .append("   End: ").append(statistics.end())
                .append("   Elapsed: ").append(statistics.elapsed())
                .append("\nIdle time (all CPUs): ").append(statistics.totalIdle())
                .append("   Utilization: ").append(percent(statistics.utilization()))
                .append("\nAverage Tr: ").append(decimal(statistics.meanTurnaround()))
                .append("   Average Tr/Ts: ").append(decimal(statistics.meanNormalizedTurnaround()))
                .append("\nAverage Response: ").append(decimal(statistics.meanResponse()))
                .append("   Average Wait: ").append(decimal(statistics.meanWait()));

        text.append("\n\nPER CPU");
        for (CpuStatistics cpu : statistics.cpus()) {
            text.append("\nCPU ").append(cpu.cpu().value())
                    .append(": busy ").append(cpu.busyTicks())
                    .append(", idle ").append(cpu.idleTicks())
                    .append(", utilization ").append(percent(cpu.utilization()))
                    .append(", completed ").append(cpu.completedProcesses())
                    .append(", average Tr ").append(decimal(cpu.meanTurnaround()))
                    .append(", average Tr/Ts ").append(decimal(cpu.meanNormalizedTurnaround()));
        }

        text.append("\n\nGANTT (PID per second, - = idle)");
        for (CpuTimelineView timeline : statistics.timeline()) {
            text.append("\n").append(ganttLine(timeline));
        }
        return text.toString();
    }
}
