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

/** Ejemplos HRRN resueltos a mano reproducidos de punta a punta. */
class HrrnSchedulingSessionTest {

    private static SimulatorOrchestrator session(int cpus) {
        var run = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
        run.initialize(new SimulatorConfiguration(new MemoryConfiguration(1024, cpus * 140), 2048, 256, cpus,
                SchedulingPolicyId.HRRN, UserAllocationStrategy.FIRST_FIT));
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
    void stallingsExampleChoosesByResponseRatio() {
        var run = session(1);
        run.submitProgram(program("A", 3), 0);
        run.submitProgram(program("B", 6), 2);
        run.submitProgram(program("C", 4), 4);
        run.submitProgram(program("D", 5), 6);
        run.submitProgram(program("E", 2), 8);
        run.start();
        assertEquals(List.of("AAABBBBBBCCCCEEDDDDD"), runToCompletion(run, 1));
        long[] finish = {3, 9, 13, 20, 15};
        var records = byPid(run);
        for (int i = 0; i < finish.length; i++) {
            assertEquals(finish[i], records.get(i + 1).logicalTimes().finishTime().orElseThrow());
        }
    }

    @Test
    void isNotPreemptive() {
        var run = session(1);
        run.submitProgram(program("A", 5), 0);
        run.submitProgram(program("B", 1), 1);
        run.start();
        assertEquals(List.of("AAAAAB"), runToCompletion(run, 1));
    }

    @Test
    void eachCpuChoosesWithItsOwnQueue() {
        var run = session(2);
        // Afinidad por admisión (CPU con menos procesos vivos): P1,P3 -> CPU0 ; P2,P4,P5 -> CPU1
        // (P5 llega en t=2, cuando P2 ya terminó y CPU1 tiene menos carga).
        run.submitProgram(program("P1", 4), 0);
        run.submitProgram(program("P2", 2), 0);
        run.submitProgram(program("P3", 3), 1);
        run.submitProgram(program("P4", 1), 1);
        run.submitProgram(program("P5", 1), 2);
        run.start();
        // CPU1 t=2: P4 (S1,W1 -> 2.0) vs P5 (S1,W0 -> 1.0) -> P4; CPU0 corre P1 y luego P3 sin influencia de CPU1.
        assertEquals(List.of("AAAACCC", "BBDE---"), runToCompletion(run, 2));
    }
}
