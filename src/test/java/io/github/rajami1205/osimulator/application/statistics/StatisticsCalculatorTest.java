package io.github.rajami1205.osimulator.application.statistics;

import io.github.rajami1205.osimulator.application.process.CompletedProcessRecord;
import io.github.rajami1205.osimulator.model.cpu.CpuContext;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.process.LogicalProcessTimes;
import io.github.rajami1205.osimulator.model.process.ProcessAccounting;
import io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsCalculatorTest {

    private static final double EPSILON = 1e-9;
    private static final CpuId CPU1 = new CpuId(1);

    private static CompletedProcessRecord record(int pid, CpuId cpu, long arrival, int serviceTime, long start,
            long finish, long executed) {
        var times = new LogicalProcessTimes(arrival, OptionalLong.of(arrival), OptionalLong.of(start),
                OptionalLong.of(finish));
        var accounting = new ProcessAccounting(OptionalInt.of(cpu.value()), Optional.of(Instant.EPOCH), executed,
                Optional.empty());
        return new CompletedProcessRecord(pid, CpuContext.<Instruction>initial(), accounting, times, serviceTime, cpu);
    }

    /** Slots de un Gantt: 0 = CPU ociosa en ese tick. */
    private static CpuTimelineView timeline(CpuId cpu, Integer... pids) {
        List<Optional<Integer>> slots = Arrays.stream(pids)
                .map(pid -> pid == 0 ? Optional.<Integer>empty() : Optional.of(pid))
                .toList();
        return new CpuTimelineView(cpu, slots);
    }

    /**
     * CPU0: P1 (Ts 2, 0-2), P2 (Ts 1, 2-3), ociosa en t3. CPU1: P3 (Ts 4, 0-4). Todos llegan en 0.
     * Tr/Ts: P1 1.0, P2 3.0, P3 1.0 -> media global 5/3; medias por CPU 2.0 y 1.0 (su media sería 1.5).
     */
    private static RunStatistics unevenTwoCpuRun() {
        var completed = List.of(
                record(2, CpuId.ZERO, 0, 1, 2, 3, 1),
                record(1, CpuId.ZERO, 0, 2, 0, 2, 2),
                record(3, CPU1, 0, 4, 0, 4, 4));
        var gantt = List.of(timeline(CpuId.ZERO, 1, 1, 2, 0), timeline(CPU1, 3, 3, 3, 3));
        return StatisticsCalculator.compute(SchedulingPolicyId.FCFS, 4, true, completed, gantt);
    }

    @Test
    void computesPerProcessStatisticsWithClassConvention() {
        var run = unevenTwoCpuRun();
        var p2 = run.processes().get(1);

        assertEquals(List.of(1, 2, 3), run.processes().stream().map(ProcessStatistics::processId).toList());
        assertEquals(CpuId.ZERO, p2.cpu());
        assertEquals(1, p2.serviceTime());
        assertEquals(2, p2.start());
        assertEquals(3, p2.finish());
        assertEquals(3, p2.turnaround());              // Tr = Finish − Arrival
        assertEquals(3.0, p2.normalizedTurnaround(), EPSILON);   // Tr / Ts
        assertEquals(2, p2.response());                // Start − Arrival
        assertEquals(2, p2.waitingTime());             // Tr − ejecutadas
    }

    @Test
    void globalMeanRatioIsTheMeanOfAllProcessRatiosNotOfCpuMeans() {
        var run = unevenTwoCpuRun();

        assertEquals(2.0, run.cpus().get(0).meanNormalizedTurnaround(), EPSILON);
        assertEquals(1.0, run.cpus().get(1).meanNormalizedTurnaround(), EPSILON);
        assertEquals(5.0 / 3.0, run.meanNormalizedTurnaround(), EPSILON);
        assertEquals(3.0, run.meanTurnaround(), EPSILON);          // (2 + 3 + 4) / 3
        assertEquals(7.0 / 3.0, run.meanServiceTime(), EPSILON);   // (2 + 1 + 4) / 3
    }

    @Test
    void computesIdleAndUtilizationPerCpuAndGlobally() {
        var run = unevenTwoCpuRun();
        var cpu0 = run.cpus().get(0);
        var cpu1 = run.cpus().get(1);

        assertEquals(SchedulingPolicyId.FCFS, run.policy());
        assertEquals(0, run.start());
        assertEquals(4, run.end());
        assertEquals(3, cpu0.busyTicks());
        assertEquals(1, cpu0.idleTicks());
        assertEquals(0.75, cpu0.utilization(), EPSILON);
        assertEquals(2, cpu0.completedProcesses());
        assertEquals(0, cpu1.idleTicks());
        assertEquals(1.0, cpu1.utilization(), EPSILON);
        assertEquals(1, run.totalIdle());
        assertEquals(7.0 / 8.0, run.utilization(), EPSILON);       // 7 ocupados / (2 CPUs · 4)
    }

    @Test
    void normalizedTurnaroundUsesDeclaredBurstEvenWhenLoopsExecuteMore() {
        var looping = record(1, CpuId.ZERO, 0, 2, 0, 5, 5);
        var run = StatisticsCalculator.compute(SchedulingPolicyId.FCFS, 5, true, List.of(looping),
                List.of(timeline(CpuId.ZERO, 1, 1, 1, 1, 1)));
        var process = run.processes().getFirst();

        assertEquals(5, process.executedInstructions());
        assertEquals(2.5, process.normalizedTurnaround(), EPSILON);   // 5 / Ts 2, no 5 / 5
        assertEquals(0, process.waitingTime());
    }

    @Test
    void emptyRunAtTimeZeroHasZeroMeansAndUtilization() {
        var run = StatisticsCalculator.compute(SchedulingPolicyId.RR, 0, false, List.of(),
                List.of(timeline(CpuId.ZERO)));

        assertFalse(run.complete());
        assertEquals(0, run.processes().size());
        assertEquals(0.0, run.meanTurnaround(), EPSILON);
        assertEquals(0.0, run.utilization(), EPSILON);
        assertEquals(0, run.totalIdle());
    }

    @Test
    void historyCopyKeepsEverythingButTheRunNumber() {
        var run = unevenTwoCpuRun();
        var numbered = run.withRunNumber(3);

        assertEquals(0, run.runNumber());
        assertEquals(3, numbered.runNumber());
        assertEquals(run.processes(), numbered.processes());
        assertEquals(run.meanNormalizedTurnaround(), numbered.meanNormalizedTurnaround(), EPSILON);
    }

    @Test
    void rejectsMissingTimeline() {
        assertThrows(IllegalArgumentException.class,
                () -> StatisticsCalculator.compute(SchedulingPolicyId.FCFS, 0, false, List.of(), List.of()));
    }
}
