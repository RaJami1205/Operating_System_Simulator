package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.process.CompletedProcessRecord;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.instruction.IncInstruction;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.model.memory.UserAllocationStrategy;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId;
import io.github.rajami1205.osimulator.testing.ControlledClock;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Ejercicio clásico de Stallings (A–E) resuelto a mano y reproducido de punta a punta. */
class SpnSchedulingSessionTest {
    // Proceso:          A  B  C  D  E   (PID = posición + 1, admitidos en orden de llegada)
    private static final long[] ARRIVAL = {0, 2, 4, 6, 8};
    private static final int[] SERVICE = {3, 6, 4, 5, 2};

    private static SimulatorOrchestrator session(SchedulingPolicyId policy, int cpus) {
        var run = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
        run.initialize(new SimulatorConfiguration(new MemoryConfiguration(1024, cpus * 140), 2048, 256, cpus,
                policy, UserAllocationStrategy.FIRST_FIT));
        return run;
    }

    private static ProgramImage program(String name, int length) {
        return new ProgramImage(name, Collections.nCopies(length, new IncInstruction()));
    }

    /** Ejecuta hasta FINISHED y devuelve el Gantt por CPU: letra del proceso o '-' si la CPU no ejecutó. */
    private static List<String> runToCompletion(SimulatorOrchestrator run, int cpus) {
        var gantt = new ArrayList<StringBuilder>();
        for (int i = 0; i < cpus; i++) gantt.add(new StringBuilder());
        for (int guard = 0; guard < 60 && run.snapshot().simulatorState() != SimulatorState.FINISHED; guard++) {
            var results = run.step().cpuResults();
            for (int cpu = 0; cpu < cpus; cpu++) {
                var result = results.get(cpu);
                gantt.get(cpu).append(result.outcome() == RuntimeStepResult.CpuOutcome.EXECUTED
                        ? (char) ('A' + result.processId().orElseThrow() - 1) : '-');
            }
        }
        assertEquals(SimulatorState.FINISHED, run.snapshot().simulatorState());
        return gantt.stream().map(StringBuilder::toString).toList();
    }

    private static Map<Integer, CompletedProcessRecord> byPid(SimulatorOrchestrator run) {
        return run.completedProcesses().stream()
                .collect(Collectors.toMap(CompletedProcessRecord::processId, r -> r));
    }

    private static SimulatorOrchestrator classicWorkload(SchedulingPolicyId policy) {
        var run = session(policy, 1);
        for (int i = 0; i < ARRIVAL.length; i++) {
            run.submitProgram(program(String.valueOf((char) ('A' + i)), SERVICE[i]), ARRIVAL[i]);
        }
        run.start();
        return run;
    }

    @Test
    void reproducesHandComputedStallingsSpnScheduleOnOneCpu() {
        var run = classicWorkload(SchedulingPolicyId.SPN);
        // t=9: C(4), D(5), E(2) listos → E por menor Burst; luego C, luego D. Sin expropiación.
        assertEquals(List.of("AAABBBBBBEECCCCDDDDD"), runToCompletion(run, 1));
        assertEquals(20, run.snapshot().simulationTimeSeconds().orElseThrow());

        long[] finish = {3, 9, 15, 20, 11};
        long[] turnaround = {3, 7, 11, 14, 3};
        double[] normalized = {1.00, 1.17, 2.75, 2.80, 1.50};
        var records = byPid(run);
        assertEquals(5, records.size());
        double ratioSum = 0;
        long turnaroundSum = 0;
        for (int i = 0; i < 5; i++) {
            var record = records.get(i + 1);
            var times = record.logicalTimes();
            assertEquals(ARRIVAL[i], times.arrivalTime());
            assertEquals(SERVICE[i], record.declaredBurst());
            assertEquals(finish[i], times.finishTime().orElseThrow());
            long tr = times.finishTime().orElseThrow() - times.arrivalTime();
            assertEquals(turnaround[i], tr);
            double ratio = (double) tr / record.declaredBurst();
            assertEquals(normalized[i], ratio, 0.005);
            turnaroundSum += tr;
            ratioSum += ratio;
        }
        assertEquals(7.60, turnaroundSum / 5.0, 1e-9);
        assertEquals(1.84, ratioSum / 5, 0.005);
    }

    @Test
    void sameWorkloadUnderFcfsKeepsHistoricalOrder() {
        var run = classicWorkload(SchedulingPolicyId.FCFS);
        assertEquals(List.of("AAABBBBBBCCCCDDDDDEE"), runToCompletion(run, 1));
        var records = byPid(run);
        long[] finish = {3, 9, 13, 18, 20};
        for (int i = 0; i < 5; i++) {
            assertEquals(finish[i], records.get(i + 1).logicalTimes().finishTime().orElseThrow());
        }
    }

    @Test
    void eachCpuSelectsShortestAmongItsOwnReadyProcessesOnly() {
        var run = session(SchedulingPolicyId.SPN, 2);
        // leastLoaded alterna: PID1→CPU0, PID2→CPU1, PID3→CPU0, PID4→CPU1.
        int[] burst = {5, 4, 1, 3};
        for (int i = 0; i < burst.length; i++) run.submitProgram(program("P" + (i + 1), burst[i]), 0);
        run.start();
        var gantt = runToCompletion(run, 2);
        // CPU0 = {PID1:5, PID3:1} → C primero; CPU1 = {PID2:4, PID4:3} → D primero, aunque C sea el global mínimo.
        assertEquals(List.of("CAAAAA-", "DDDBBBB"), gantt);
        var records = byPid(run);
        int[] affinity = {0, 1, 0, 1};
        long[] finish = {6, 7, 1, 3};
        for (int i = 0; i < burst.length; i++) {
            var record = records.get(i + 1);
            assertEquals(affinity[i], record.cpuAffinity().value());
            assertEquals(finish[i], record.logicalTimes().finishTime().orElseThrow());
        }
    }
}
