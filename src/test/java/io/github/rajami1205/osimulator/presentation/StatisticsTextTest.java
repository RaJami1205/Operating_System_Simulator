package io.github.rajami1205.osimulator.presentation;

import io.github.rajami1205.osimulator.application.process.CompletedProcessRecord;
import io.github.rajami1205.osimulator.application.statistics.CpuTimelineView;
import io.github.rajami1205.osimulator.application.statistics.RunStatistics;
import io.github.rajami1205.osimulator.application.statistics.StatisticsCalculator;
import io.github.rajami1205.osimulator.model.cpu.CpuContext;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.process.LogicalProcessTimes;
import io.github.rajami1205.osimulator.model.process.ProcessAccounting;
import io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsTextTest {

    private static CompletedProcessRecord record(int pid, long arrival, int serviceTime, long start, long finish) {
        var times = new LogicalProcessTimes(arrival, OptionalLong.of(arrival), OptionalLong.of(start),
                OptionalLong.of(finish));
        var accounting = new ProcessAccounting(OptionalInt.of(0), Optional.of(Instant.EPOCH), serviceTime,
                Optional.empty());
        return new CompletedProcessRecord(pid, CpuContext.<Instruction>initial(), accounting, times, serviceTime,
                CpuId.ZERO);
    }

    /** A (0, Ts 2) corre t0-1; ociosa t2; B (3, Ts 1) corre t3. */
    private static RunStatistics run() {
        var timeline = new CpuTimelineView(CpuId.ZERO,
                List.of(Optional.of(1), Optional.of(1), Optional.empty(), Optional.of(2)));
        var statistics = StatisticsCalculator.compute(SchedulingPolicyId.FCFS, 4, true,
                List.of(record(1, 0, 2, 0, 2), record(2, 3, 1, 3, 4)), List.of(timeline));
        return statistics.withRunNumber(1);
    }

    @Test
    void processRowsEndWithAnAverageRow() {
        var rows = StatisticsText.processRows(run());

        assertEquals(3, rows.size());
        var first = rows.getFirst();
        assertEquals("1", first.pid());
        assertEquals("2", first.serviceTime());
        assertEquals("2", first.finish());
        assertEquals("2", first.turnaround());
        assertEquals("1.00", first.normalizedTurnaround());
        var average = rows.getLast();
        assertEquals("Average", average.pid());
        assertEquals("1.50", average.serviceTime());
        assertEquals("1.50", average.turnaround());
        assertEquals("1.00", average.normalizedTurnaround());
    }

    @Test
    void emptyRunHasNoAverageRow() {
        var empty = StatisticsCalculator.compute(SchedulingPolicyId.RR, 0, false, List.of(),
                List.of(new CpuTimelineView(CpuId.ZERO, List.of())));

        assertTrue(StatisticsText.processRows(empty).isEmpty());
    }

    @Test
    void ganttLineShowsPidPerSecondAndDashWhenIdle() {
        assertEquals("CPU 0 | 1 1 - 2", StatisticsText.ganttLine(run().timeline().getFirst()));
    }

    @Test
    void historyRowAndRunLabelIncludeTheAlgorithm() {
        var row = StatisticsText.historyRows(List.of(run())).getFirst();

        assertEquals("#1", row.run());
        assertEquals("FCFS", row.algorithm());
        assertEquals("0", row.start());
        assertEquals("4", row.end());
        assertEquals("1", row.idle());
        assertEquals("75.0%", row.utilization());
        assertEquals("Run #1 — FCFS, 1 CPU", StatisticsText.runLabel(run()));
    }

    @Test
    void summaryListsTheRequiredRunData() {
        String summary = StatisticsText.summary(run());

        assertTrue(summary.contains("Run #1 — finished"));
        assertTrue(summary.contains("Algorithm: FCFS"));
        assertTrue(summary.contains("Start: 0   End: 4"));
        assertTrue(summary.contains("Idle time (all CPUs): 1"));
        assertTrue(summary.contains("Average Tr/Ts: 1.00"));
        assertTrue(summary.contains("CPU 0: busy 3, idle 1"));
        assertTrue(summary.contains("CPU 0 | 1 1 - 2"));
    }
}
