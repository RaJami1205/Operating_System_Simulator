package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.configuration.LotteryConfiguration;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Lottery de punta a punta. El Gantt depende del generador, así que se verifican propiedades: misma semilla →
 * mismo resultado, los tickets favorecen al proceso, cada CPU sortea de forma independiente.
 */
class LotterySchedulingSessionTest {

    private static SimulatorOrchestrator session(int cpus, int quantum, LotteryConfiguration lottery) {
        var run = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
        run.initialize(new SimulatorConfiguration(new MemoryConfiguration(1024, cpus * 140), 2048, 256, cpus,
                SchedulingPolicyId.LOTTERY, UserAllocationStrategy.FIRST_FIT, MemoryMode.DYNAMIC, Optional.empty(),
                Optional.empty(), quantum, PriorityConfiguration.defaults(), lottery));
        return run;
    }

    private static ProgramImage program(String name, int length) {
        return new ProgramImage(name, Collections.nCopies(length, new IncInstruction()));
    }

    /** Gantt por CPU: letra del proceso (PID 1 = 'A') o '-' si la CPU no ejecutó en ese tick. */
    private static List<String> runToCompletion(SimulatorOrchestrator run, int cpus) {
        var gantt = new ArrayList<StringBuilder>();
        for (int i = 0; i < cpus; i++) gantt.add(new StringBuilder());
        for (int guard = 0; guard < 200 && run.snapshot().simulatorState() != SimulatorState.FINISHED; guard++) {
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

    private static List<String> threeProcessRun(long seed) {
        var run = session(1, 1, LotteryConfiguration.withSeed(seed));
        run.submitProgram(program("A", 4), 0, 0, 3);
        run.submitProgram(program("B", 4), 0, 0, 2);
        run.submitProgram(program("C", 4), 0, 0, 1);
        run.start();
        return runToCompletion(run, 1);
    }

    @Test
    void sameSeedReproducesTheSameGantt() {
        List<String> first = threeProcessRun(42);
        List<String> second = threeProcessRun(42);
        assertEquals(first, second);
        assertEquals(12, first.getFirst().length(), "every instruction runs exactly once");
    }

    @Test
    void moreTicketsWinMostDraws() {
        var run = session(1, 1, LotteryConfiguration.withSeed(7));
        run.submitProgram(program("A", 20), 0, 0, 99);
        run.submitProgram(program("B", 20), 0, 0, 1);
        run.start();
        String gantt = runToCompletion(run, 1).getFirst();
        // Con 99 contra 1 tickets y quantum 1, A recibe casi todos los ticks mientras ambos compiten.
        String whileBothCompete = gantt.substring(0, gantt.lastIndexOf('A') + 1);
        long ticksOfB = whileBothCompete.chars().filter(ch -> ch == 'B').count();
        assertTrue(ticksOfB <= 3, "B ran " + ticksOfB + " ticks while competing: " + gantt);
        assertTrue(gantt.endsWith("B"), gantt);
    }

    @Test
    void eachCpuDrawsIndependently() {
        // Afinidad por admisión: P1,P3 -> CPU0 ; P2,P4 -> CPU1. Cambiar los tickets de CPU0 no altera CPU1.
        List<String> baseline = twoCpuRun(1, 1);
        List<String> changedCpu0 = twoCpuRun(50, 50);
        assertEquals(baseline.get(1), changedCpu0.get(1));
    }

    private static List<String> twoCpuRun(int ticketsP1, int ticketsP3) {
        var run = session(2, 1, LotteryConfiguration.withSeed(11));
        run.submitProgram(program("P1", 3), 0, 0, ticketsP1);
        run.submitProgram(program("P2", 3), 0, 0, 1);
        run.submitProgram(program("P3", 3), 0, 0, ticketsP3);
        run.submitProgram(program("P4", 3), 0, 0, 4);
        run.start();
        return runToCompletion(run, 2);
    }

    @Test
    void blankSeedIsResolvedOnceAndPublished() {
        var run = session(1, 2, LotteryConfiguration.defaults());
        var seed = run.configuration().orElseThrow().lottery().seed();
        assertTrue(seed.isPresent(), "a random seed is fixed at initialize so the run can be repeated");
        assertTrue(seed.getAsLong() >= LotteryConfiguration.MIN_SEED && seed.getAsLong() <= LotteryConfiguration.MAX_SEED,
                "random seed stays short and positive: " + seed.getAsLong());
    }

    @Test
    void seedOutsideRangeIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> LotteryConfiguration.withSeed(0));
        assertThrows(IllegalArgumentException.class, () -> LotteryConfiguration.withSeed(-5));
        assertThrows(IllegalArgumentException.class, () -> LotteryConfiguration.withSeed(1_000_000));
        assertEquals(999_999, LotteryConfiguration.withSeed(999_999).seed().getAsLong());
    }

    @Test
    void ticketsReachThePcbAndTheSnapshot() {
        var run = session(1, 2, LotteryConfiguration.withSeed(1));
        run.submitProgram(program("A", 2), 0, 0, 37);
        run.start();
        run.step();
        var details = run.snapshot().processes().stream().filter(p -> p.processId() == 1).findFirst().orElseThrow();
        assertEquals(37, details.tickets());
    }

    @Test
    void ticketsOutsideRangeAreRejectedOnSubmit() {
        var run = session(1, 2, LotteryConfiguration.withSeed(1));
        assertThrows(IllegalArgumentException.class, () -> run.submitProgram(program("A", 2), 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> run.submitProgram(program("A", 2), 0, 0, 101));
    }
}
