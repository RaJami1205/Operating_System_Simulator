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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ejemplos Round Robin (quantum 2) resueltos a mano reproducidos de punta a punta. */
class RoundRobinSchedulingSessionTest {

    private static SimulatorOrchestrator session(int cpus) {
        var run = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
        run.initialize(new SimulatorConfiguration(new MemoryConfiguration(1024, cpus * 140), 2048, 256, cpus,
                SchedulingPolicyId.RR, UserAllocationStrategy.FIRST_FIT));
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
    void rotatesWithQuantumTwo() {
        var run = session(1);
        run.submitProgram(program("A", 3), 0);
        run.submitProgram(program("B", 2), 0);
        run.submitProgram(program("C", 1), 0);
        run.start();
        assertEquals(List.of("AABBCA"), runToCompletion(run, 1));
        var records = byPid(run);
        assertEquals(6, records.get(1).logicalTimes().finishTime().orElseThrow());
        assertEquals(4, records.get(2).logicalTimes().finishTime().orElseThrow());
        assertEquals(5, records.get(3).logicalTimes().finishTime().orElseThrow());
    }

    @Test
    void arrivalAtExpiryEntersQueueBeforeExpiredProcess() {
        var run = session(1);
        run.submitProgram(program("A", 4), 0);
        run.submitProgram(program("B", 2), 2);
        run.start();
        assertEquals(List.of("AABBAA"), runToCompletion(run, 1));
        var records = byPid(run);
        assertEquals(6, records.get(1).logicalTimes().finishTime().orElseThrow());
        assertEquals(4, records.get(2).logicalTimes().finishTime().orElseThrow());
    }

    @Test
    void eachCpuRotatesIndependently() {
        var run = session(2);
        // t=0: P1→CPU0, P2→CPU1, P3→CPU0 (empate de carga → CPU0).
        run.submitProgram(program("P1", 4), 0);
        run.submitProgram(program("P2", 4), 0);
        run.submitProgram(program("P3", 2), 0);
        run.start();
        assertEquals(List.of("AACCAA", "BBBB--"), runToCompletion(run, 2));
    }
}
