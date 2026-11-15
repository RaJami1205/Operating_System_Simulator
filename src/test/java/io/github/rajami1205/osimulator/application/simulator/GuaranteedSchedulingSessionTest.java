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

/** Ejemplos Guaranteed resueltos a mano reproducidos de punta a punta. */
class GuaranteedSchedulingSessionTest {

    private static SimulatorOrchestrator session(int cpus) {
        var run = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
        run.initialize(new SimulatorConfiguration(new MemoryConfiguration(1024, cpus * 140), 2048, 256, cpus,
                SchedulingPolicyId.GUARANTEED, UserAllocationStrategy.FIRST_FIT));
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
    void alternatesTowardTheProcessThatReceivedLessThanPromised() {
        var run = session(1);
        run.submitProgram(program("A", 4), 0);
        run.submitProgram(program("B", 3), 2);
        run.start();
        // t2: B sin servicio (ratio 0) expulsa a A.  t3: A 2/3 < B 1/1.  t4: B 1/2 < A 3/4.
        // t5: A 3/5 < B 2/3 (A termina en 6).  t6: B solo (termina en 7).
        assertEquals(List.of("AABABAB"), runToCompletion(run, 1));
        var records = byPid(run);
        assertEquals(6, records.get(1).logicalTimes().finishTime().orElseThrow());
        assertEquals(7, records.get(2).logicalTimes().finishTime().orElseThrow());
    }

    @Test
    void keepsCurrentOwnerOnRatioTie() {
        var run = session(1);
        run.submitProgram(program("A", 2), 0);
        run.submitProgram(program("B", 2), 0);
        run.start();
        // t0: empate sin servicio -> PID menor (A).  t1: B sin servicio expulsa a A.
        // t2: A 1/2 = B 1/2 -> sigue B (termina en 3).  t3: A (termina en 4).
        assertEquals(List.of("ABBA"), runToCompletion(run, 1));
        var records = byPid(run);
        assertEquals(4, records.get(1).logicalTimes().finishTime().orElseThrow());
        assertEquals(3, records.get(2).logicalTimes().finishTime().orElseThrow());
    }

    @Test
    void eachCpuEvaluatesOnlyItsOwnProcesses() {
        var run = session(2);
        // Afinidad por admisión (CPU con menos procesos vivos): P1,P3 -> CPU0 ; P2,P4 -> CPU1.
        run.submitProgram(program("P1", 3), 0);
        run.submitProgram(program("P2", 3), 0);
        run.submitProgram(program("P3", 2), 1);
        run.submitProgram(program("P4", 1), 1);
        run.start();
        // CPU0: t1 C expulsa; t2 A 1/2 < C 1/1; t3 C 1/2 < A 2/3; t4 A solo.
        // CPU1: t1 D expulsa y termina; t2-t3 B solo; t4 inactiva.
        assertEquals(List.of("ACACA", "BDBB-"), runToCompletion(run, 2));
    }
}
