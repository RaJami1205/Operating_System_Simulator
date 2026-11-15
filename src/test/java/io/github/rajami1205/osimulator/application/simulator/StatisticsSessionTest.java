package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.statistics.CpuTimelineView;
import io.github.rajami1205.osimulator.application.statistics.ProcessStatistics;
import io.github.rajami1205.osimulator.application.statistics.RunStatistics;
import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.instruction.IncInstruction;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.model.memory.UserAllocationStrategy;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId;
import io.github.rajami1205.osimulator.testing.ControlledClock;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Estadísticas de punta a punta contra la tabla 9.5 de Stallings e historial acumulado entre Reset. */
class StatisticsSessionTest {

    private static final double EPSILON = 0.005;

    private final SimulatorOrchestrator run =
            new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());

    private void initialize(SchedulingPolicyId policy, int cpus) {
        run.initialize(new SimulatorConfiguration(new MemoryConfiguration(1024, cpus * 140), 2048, 256, cpus,
                policy, UserAllocationStrategy.FIRST_FIT));
    }

    private static ProgramImage program(String name, int length) {
        return new ProgramImage(name, Collections.nCopies(length, new IncInstruction()));
    }

    private void submitStallingsWorkload() {
        run.submitProgram(program("A", 3), 0);
        run.submitProgram(program("B", 6), 2);
        run.submitProgram(program("C", 4), 4);
        run.submitProgram(program("D", 5), 6);
        run.submitProgram(program("E", 2), 8);
    }

    private RunStatistics runToCompletion() {
        run.start();
        for (int guard = 0; guard < 60 && run.snapshot().simulatorState() != SimulatorState.FINISHED; guard++) {
            run.step();
        }
        assertEquals(SimulatorState.FINISHED, run.snapshot().simulatorState());
        return run.statistics().orElseThrow();
    }

    /** Gantt como texto: letra del proceso (PID 1 = 'A') o '-' si la CPU estuvo ociosa. */
    private static String gantt(CpuTimelineView timeline) {
        return timeline.slots().stream()
                .map(slot -> slot.map(pid -> String.valueOf((char) ('A' + pid - 1))).orElse("-"))
                .collect(Collectors.joining());
    }

    private static List<Long> column(RunStatistics statistics, java.util.function.ToLongFunction<ProcessStatistics> value) {
        return statistics.processes().stream().mapToLong(value).boxed().toList();
    }

    @Test
    void fcfsMatchesStallingsTable() {
        initialize(SchedulingPolicyId.FCFS, 1);
        submitStallingsWorkload();

        var statistics = runToCompletion();

        assertTrue(statistics.complete());
        assertEquals(1, statistics.runNumber());
        assertEquals(SchedulingPolicyId.FCFS, statistics.policy());
        assertEquals("AAABBBBBBCCCCDDDDDEE", gantt(statistics.timeline().getFirst()));
        assertEquals(0, statistics.start());
        assertEquals(20, statistics.end());
        assertEquals(List.of(3L, 9L, 13L, 18L, 20L), column(statistics, ProcessStatistics::finish));
        assertEquals(List.of(3L, 7L, 9L, 12L, 12L), column(statistics, ProcessStatistics::turnaround));
        assertEquals(List.of(0L, 1L, 5L, 7L, 10L), column(statistics, ProcessStatistics::response));
        assertEquals(List.of(0L, 1L, 5L, 7L, 10L), column(statistics, ProcessStatistics::waitingTime));
        assertEquals(8.60, statistics.meanTurnaround(), EPSILON);
        assertEquals(2.56, statistics.meanNormalizedTurnaround(), EPSILON);
        assertEquals(6.0, statistics.processes().get(4).normalizedTurnaround(), EPSILON);
        assertEquals(0, statistics.totalIdle());
        assertEquals(1.0, statistics.utilization(), EPSILON);
    }

    @Test
    void mlfqMatchesStallingsTable() {
        initialize(SchedulingPolicyId.MLFQ, 1);
        submitStallingsWorkload();

        var statistics = runToCompletion();

        assertEquals(List.of(4L, 17L, 18L, 20L, 14L), column(statistics, ProcessStatistics::finish));
        assertEquals(10.60, statistics.meanTurnaround(), EPSILON);
        assertEquals(2.63, statistics.meanNormalizedTurnaround(), EPSILON);
    }

    @Test
    void idleTicksAreCountedPerCpuAndGlobally() {
        initialize(SchedulingPolicyId.FCFS, 2);
        run.submitProgram(program("P1", 2), 0);
        run.submitProgram(program("P2", 1), 3);

        var statistics = runToCompletion();
        var cpu0 = statistics.cpus().get(0);
        var cpu1 = statistics.cpus().get(1);

        // P1 corre t0-1; nada en t2 (espera la llegada); P2 llega en t3 y vuelve a CPU0 (menos cargada, empate).
        assertEquals("AA-B", gantt(statistics.timeline().get(0)));
        assertEquals("----", gantt(statistics.timeline().get(1)));
        assertEquals(3, cpu0.busyTicks());
        assertEquals(1, cpu0.idleTicks());
        assertEquals(0.75, cpu0.utilization(), EPSILON);
        assertEquals(4, cpu1.idleTicks());
        assertEquals(5, statistics.totalIdle());
        assertEquals(3.0 / 8.0, statistics.utilization(), EPSILON);
    }

    @Test
    void liveStatisticsArePartialUntilTheRunFinishes() {
        initialize(SchedulingPolicyId.FCFS, 1);
        submitStallingsWorkload();
        run.start();
        for (int tick = 0; tick < 4; tick++) {
            run.step();
        }

        var live = run.statistics().orElseThrow();

        assertFalse(live.complete());
        assertEquals(0, live.runNumber());
        assertEquals(4, live.end());
        assertEquals(List.of(1), live.processes().stream().map(ProcessStatistics::processId).toList());
        assertTrue(run.runHistory().isEmpty());
    }

    @Test
    void finishedRunsAccumulateAcrossResetAndInterruptedRunsAreNotStored() {
        initialize(SchedulingPolicyId.FCFS, 1);
        submitStallingsWorkload();
        runToCompletion();
        run.reset();

        assertEquals(Optional.empty(), run.statistics());
        assertEquals(1, run.runHistory().size());
        assertEquals(1, run.snapshot().runHistory().size());

        initialize(SchedulingPolicyId.RR, 1);
        submitStallingsWorkload();
        run.start();
        run.step();
        run.reset();
        assertEquals(1, run.runHistory().size());   // la ejecución interrumpida no se guarda

        initialize(SchedulingPolicyId.MLFQ, 1);
        submitStallingsWorkload();
        runToCompletion();

        var history = run.runHistory();
        assertEquals(2, history.size());
        assertEquals(List.of(1, 2), history.stream().map(RunStatistics::runNumber).toList());
        assertEquals(List.of(SchedulingPolicyId.FCFS, SchedulingPolicyId.MLFQ),
                history.stream().map(RunStatistics::policy).toList());
        assertEquals(8.60, history.get(0).meanTurnaround(), EPSILON);
        assertEquals(10.60, history.get(1).meanTurnaround(), EPSILON);
        assertEquals(5, history.get(0).processes().size());
        assertEquals(run.statistics(), run.snapshot().statistics());
    }
}
