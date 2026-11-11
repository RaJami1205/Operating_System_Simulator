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

/** Ejemplos SRT resueltos a mano (carpeta de samples srt) reproducidos de punta a punta. */
class SrtSchedulingSessionTest {

    private static SimulatorOrchestrator session(int cpus) {
        var run = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
        run.initialize(new SimulatorConfiguration(new MemoryConfiguration(1024, cpus * 140), 2048, 256, cpus,
                SchedulingPolicyId.SRT, UserAllocationStrategy.FIRST_FIT));
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
    void smokeTestPicksShortestFirstWithAllArrivalsAtZero() {
        var run = session(1);
        run.submitProgram(program("A", 3), 0);
        run.submitProgram(program("B", 2), 0);
        run.start();
        assertEquals(List.of("BBAAA"), runToCompletion(run, 1));
    }

    @Test
    void preemptsOnShorterRemainingAndKeepsCurrentOnTie() {
        var run = session(1);
        run.submitProgram(program("A", 5), 0);
        run.submitProgram(program("B", 2), 1);
        run.submitProgram(program("C", 1), 2);
        run.submitProgram(program("D", 3), 3);
        run.start();
        assertEquals(List.of("ABBCDDDAAAA"), runToCompletion(run, 1));

        long[] finish = {11, 3, 4, 7};
        var records = byPid(run);
        for (int i = 0; i < finish.length; i++) {
            assertEquals(finish[i], records.get(i + 1).logicalTimes().finishTime().orElseThrow());
        }
    }

    @Test
    void eachCpuPreemptsIndependently() {
        var run = session(2);
        // La afinidad se decide al ADMITIR (CPU menos cargada, empate → CPU0) y el PID sigue el orden de
        // admisión: t=0 P1→CPU0 (A), P2→CPU1 (B); t=2 P3→CPU0 (C); t=3 P4→CPU1 (D).
        run.submitProgram(program("P1", 6), 0);
        run.submitProgram(program("P2", 5), 0);
        run.submitProgram(program("P3", 2), 2);
        run.submitProgram(program("P4", 1), 3);
        run.start();
        assertEquals(List.of("AACCAAAA", "BBBDBB--"), runToCompletion(run, 2));
    }
}
