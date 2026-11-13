package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.process.CompletedProcessRecord;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.configuration.PriorityConfiguration;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.instruction.IncInstruction;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.model.memory.MemoryMode;
import io.github.rajami1205.osimulator.model.memory.UserAllocationStrategy;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId;
import io.github.rajami1205.osimulator.testing.ControlledClock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Ejemplos Priority (menor número = más prioridad) resueltos a mano y reproducidos de punta a punta. */
class PrioritySchedulingSessionTest {

    private static SimulatorOrchestrator session(int cpus, boolean preemptive, int agingInterval) {
        var run = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
        run.initialize(new SimulatorConfiguration(new MemoryConfiguration(1024, cpus * 140), 2048, 256, cpus,
                SchedulingPolicyId.PRIORITY, UserAllocationStrategy.FIRST_FIT, MemoryMode.DYNAMIC, Optional.empty(),
                Optional.empty(), SimulatorConfiguration.DEFAULT_QUANTUM,
                new PriorityConfiguration(preemptive, agingInterval)));
        return run;
    }

    private static ProgramImage program(String name, int length) {
        return new ProgramImage(name, Collections.nCopies(length, new IncInstruction()));
    }

    /** Gantt por CPU: letra del proceso (PID 1 = 'A') o '-' si la CPU no ejecutó en ese tick. */
    private static List<String> runToCompletion(SimulatorOrchestrator run, int cpus) {
        var gantt = new ArrayList<StringBuilder>();
        for (int i = 0; i < cpus; i++) gantt.add(new StringBuilder());
        for (int guard = 0; guard < 60 && run.snapshot().simulatorState() != SimulatorState.FINISHED; guard++) {
            var results = run.step().cpuResults();
            for (int cpu = 0; cpu < cpus; cpu++) {
                var result = results.get(cpu);
                boolean executed = result.outcome() == RuntimeStepResult.CpuOutcome.EXECUTED;
                gantt.get(cpu).append(executed ? (char) ('A' + result.processId().orElseThrow() - 1) : '-');
            }
        }
        assertEquals(SimulatorState.FINISHED, run.snapshot().simulatorState());
        return gantt.stream().map(StringBuilder::toString).toList();
    }

    private static Map<Integer, CompletedProcessRecord> byPid(SimulatorOrchestrator run) {
        return run.completedProcesses().stream()
                .collect(Collectors.toMap(CompletedProcessRecord::processId, r -> r));
    }

    private static void assertFinish(SimulatorOrchestrator run, long... finish) {
        var records = byPid(run);
        for (int i = 0; i < finish.length; i++) {
            assertEquals(finish[i], records.get(i + 1).logicalTimes().finishTime().orElseThrow());
        }
    }

    /** A(arr0,S4,p2) B(arr1,S3,p1) C(arr2,S2,p0) D(arr3,S1,p1). */
    private static void loadClassExercise(SimulatorOrchestrator run) {
        run.submitProgram(program("A", 4), 0, 2);
        run.submitProgram(program("B", 3), 1, 1);
        run.submitProgram(program("C", 2), 2, 0);
        run.submitProgram(program("D", 1), 3, 1);
    }

    @Test
    void nonPreemptiveRunsOwnerToCompletion() {
        var run = session(1, false, 0);
        loadClassExercise(run);
        run.start();
        assertEquals(List.of("AAAACCBBBD"), runToCompletion(run, 1));
        assertFinish(run, 4, 9, 6, 10);
    }

    @Test
    void preemptiveExpelsOnStrictlyBetterPriority() {
        var run = session(1, true, 0);
        loadClassExercise(run);
        run.start();
        assertEquals(List.of("ABCCBBDAAA"), runToCompletion(run, 1));
        assertFinish(run, 10, 6, 4, 7);
    }

    @Test
    void processPriorityReachesThePcb() {
        var run = session(1, false, 0);
        run.submitProgram(program("A", 2), 0, 7);
        run.start();
        run.step();
        var details = run.snapshot().processes().stream().filter(p -> p.processId() == 1).findFirst().orElseThrow();
        assertEquals(7, details.priority());
    }

    @Test
    void priorityOutsideRangeIsRejectedOnSubmit() {
        var run = session(1, false, 0);
        assertThrows(IllegalArgumentException.class, () -> run.submitProgram(program("A", 2), 0, 10));
        assertThrows(IllegalArgumentException.class, () -> run.submitProgram(program("A", 2), 0, -1));
    }

    /** A(arr0,S1,p3) es adelantado por B,C,D,E (p0, S3, llegan 0/3/6/9). */
    private static void loadStarvationCase(SimulatorOrchestrator run) {
        run.submitProgram(program("A", 1), 0, 3);
        run.submitProgram(program("B", 3), 0, 0);
        run.submitProgram(program("C", 3), 3, 0);
        run.submitProgram(program("D", 3), 6, 0);
        run.submitProgram(program("E", 3), 9, 0);
    }

    @Test
    void withoutAgingTheLowPriorityProcessWaitsUntilTheEnd() {
        var run = session(1, false, 0);
        loadStarvationCase(run);
        run.start();
        assertEquals(List.of("BBBCCCDDDEEEA"), runToCompletion(run, 1));
    }

    @Test
    void agingEveryThreeTicksLetsTheLowPriorityProcessRunEarlier() {
        var run = session(1, false, 3);
        loadStarvationCase(run);
        run.start();
        // t=9: A efectiva 3 - 9/3 = 0, empata con E (0) y gana por Arrival.
        assertEquals(List.of("BBBCCCDDDAEEE"), runToCompletion(run, 1));
        assertFinish(run, 10, 3, 6, 9, 13);
    }

    @Test
    void eachCpuChoosesWithItsOwnQueue() {
        var run = session(2, false, 0);
        // Afinidad por admisión: P1,P3 -> CPU0 ; P2,P4 -> CPU1.
        run.submitProgram(program("P1", 2), 0, 5);
        run.submitProgram(program("P2", 2), 0, 5);
        run.submitProgram(program("P3", 1), 0, 0);
        run.submitProgram(program("P4", 1), 0, 9);
        run.start();
        assertEquals(List.of("CAA", "BBD"), runToCompletion(run, 2));
    }
}
