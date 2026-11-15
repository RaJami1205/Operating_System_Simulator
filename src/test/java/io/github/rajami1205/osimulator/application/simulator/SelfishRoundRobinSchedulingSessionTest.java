package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.process.CompletedProcessRecord;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
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

/** Ejemplos Selfish Round Robin (a = 2, b = 1 fijos) resueltos a mano reproducidos de punta a punta. */
class SelfishRoundRobinSchedulingSessionTest {

    private static SimulatorOrchestrator session(int cpus, int quantum) {
        var run = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
        run.initialize(new SimulatorConfiguration(new MemoryConfiguration(1024, cpus * 140), 2048, 256, cpus,
                SchedulingPolicyId.SELFISH_RR, UserAllocationStrategy.FIRST_FIT, MemoryMode.DYNAMIC,
                Optional.empty(), Optional.empty(), quantum));
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

    @Test
    void newProcessWaitsUntilItsPriorityCatchesUpWithTheAcceptedOne() {
        var run = session(1, 2);
        run.submitProgram(program("A", 8), 0);
        run.submitProgram(program("B", 2), 3);
        run.start();
        // q=2. A aceptado en t0 (prio = t). B nuevo (prio = 2·(t−3)):
        // t3 B0<A3, t4 B2<A4, t5 B4<A5, t6 B6=A6 -> aceptado; A agotó su quantum -> B corre t6-t7.
        assertEquals(List.of("AAAAAABBAA"), runToCompletion(run, 1));
        var records = byPid(run);
        assertEquals(10, records.get(1).logicalTimes().finishTime().orElseThrow());
        assertEquals(8, records.get(2).logicalTimes().finishTime().orElseThrow());
    }

    @Test
    void eachCpuAcceptsAndRotatesOnlyItsOwnProcesses() {
        var run = session(2, 2);
        // Afinidad por admisión (CPU con menos procesos vivos): P1,P3 -> CPU0 ; P2,P4 -> CPU1.
        run.submitProgram(program("P1", 4), 0);
        run.submitProgram(program("P2", 5), 0);
        run.submitProgram(program("P3", 2), 1);
        run.submitProgram(program("P4", 1), 1);
        run.start();
        // CPU0: t1 C0<A1 espera; t2 C2=A2 aceptado y A agotó quantum -> C t2-t3; A t4-t5.
        // CPU1: t1 D0<B1 espera; t2 D2=B2 aceptado -> D t2; B t3-t5.
        assertEquals(List.of("AACCAA", "BBDBBB"), runToCompletion(run, 2));
    }
}
