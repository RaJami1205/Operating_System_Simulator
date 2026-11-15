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

/** Ejemplos MLFQ (3 niveles, quantum 1/2/4, sin boost) resueltos a mano reproducidos de punta a punta. */
class MlfqSchedulingSessionTest {

    private static SimulatorOrchestrator session(int cpus) {
        var run = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
        run.initialize(new SimulatorConfiguration(new MemoryConfiguration(1024, cpus * 140), 2048, 256, cpus,
                SchedulingPolicyId.MLFQ, UserAllocationStrategy.FIRST_FIT));
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

    private static void assertFinishTimes(SimulatorOrchestrator run, long... finish) {
        var records = byPid(run);
        for (int i = 0; i < finish.length; i++) {
            assertEquals(finish[i], records.get(i + 1).logicalTimes().finishTime().orElseThrow());
        }
    }

    @Test
    void stallingsFeedbackExampleWithPowerOfTwoQuanta() {
        var run = session(1);
        run.submitProgram(program("A", 3), 0);
        run.submitProgram(program("B", 6), 2);
        run.submitProgram(program("C", 4), 4);
        run.submitProgram(program("D", 5), 6);
        run.submitProgram(program("E", 2), 8);
        run.start();
        // Stallings, tabla 9.5 "FB q = 2^i": A=4, B=17, C=18, D=20, E=14.
        // t1: A solo no baja. t2: llega B, A -> nivel 1. t3: B -> 1, A termina (q2). t5: C -> 1.
        // t7: B -> 2. t8/t9: D y E -> 1. t11: C -> 2. t13: D -> 2, E termina. t14+: nivel 2 en RR (q4).
        assertEquals(List.of("AABACBBDECCDDEBBBCDD"), runToCompletion(run, 1));
        assertFinishTimes(run, 4, 17, 18, 20, 14);
    }

    @Test
    void higherLevelArrivalWaitsForTheRunningQuantumToEnd() {
        var run = session(1);
        run.submitProgram(program("A", 6), 0);
        run.submitProgram(program("B", 4), 1);
        run.submitProgram(program("C", 1), 3);
        run.start();
        // t3: C llega a nivel 0 mientras A va por 1 de 2 en nivel 1: espera hasta t4.
        // t4: A -> 2, C corre. t5-6: B (nivel 1). t7: B -> 2; nivel 2 en RR: A t7-9, B t10.
        assertEquals(List.of("ABAACBBAAAB"), runToCompletion(run, 1));
        assertFinishTimes(run, 10, 11, 5);
    }

    @Test
    void eachCpuKeepsItsOwnLevels() {
        var run = session(2);
        // Afinidad por admisión (CPU con menos procesos vivos): P1,P3 -> CPU0 ; P2,P4 -> CPU1.
        run.submitProgram(program("P1", 3), 0);
        run.submitProgram(program("P2", 4), 0);
        run.submitProgram(program("P3", 2), 1);
        run.submitProgram(program("P4", 1), 1);
        run.start();
        // CPU0: t1 A -> 1, C corre; t2 C -> 1 detrás de A; A t2-3 (q2); C t4.
        // CPU1: t1 B -> 1, D corre y termina; B solo t2-4 (agota q2 en t4 pero sigue: está solo).
        assertEquals(List.of("ACAAC", "BDBBB"), runToCompletion(run, 2));
    }
}
