package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.execution.*;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.job.JobState;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkloadArrivalsTest {
    @Test void editedArrivalControlsAdmissionAndFinalStatistics() {
        assertThrows(IllegalStateException.class, () -> simulator.updateJobArrival(1, 2));
        simulator.initialize(SimulatorConfiguration.defaults());
        assertThrows(IllegalStateException.class, () -> simulator.updateJobArrival(1, 2));
        submit("first", 5, "INC", "INC");
        submit("second", 1, "INC");
        var before = simulator.snapshot();
        simulator.updateJobArrival(1, 1);
        var edited = simulator.snapshot();
        assertEquals(before.storage(), edited.storage());
        assertEquals(before.memory(), edited.memory());
        assertTrue(edited.processes().isEmpty());
        assertEquals(0, time());
        simulator.start();
        assertThrows(IllegalStateException.class, () -> simulator.updateJobArrival(1, 2));
        simulator.pause();
        assertThrows(IllegalStateException.class, () -> simulator.updateJobArrival(1, 2));
        simulator.resume();
        for (int i = 0; i < 4; i++) simulator.step();
        assertEquals(SimulatorState.FINISHED, simulator.snapshot().simulatorState());
        assertThrows(IllegalStateException.class, () -> simulator.updateJobArrival(1, 2));
        var completed = simulator.completedProcesses();
        assertEquals(2, completed.getFirst().declaredBurst());
        assertEquals(1, completed.getFirst().logicalTimes().arrivalTime());
        var stats = simulator.statistics().orElseThrow().processes();
        assertEquals(2, stats.getFirst().turnaround());
        assertEquals(1.0, stats.getFirst().normalizedTurnaround());
        assertEquals(2, stats.getLast().response());
        assertEquals(2, stats.getLast().waitingTime());
        simulator.reset();
        assertTrue(simulator.jobs().isEmpty());
    }

    @Test void affinityIsAssignedInEditedAdmissionOrder() {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(512, 280), 512, 64, 2));
        submit("first", 0, "INC");
        submit("second", 0, "INC", "INC");
        simulator.updateJobArrival(1, 1);
        simulator.start();
        var firstAdmitted = simulator.snapshot().processes().getFirst();
        assertEquals(2, firstAdmitted.limit());
        simulator.step();
        var secondAdmitted = simulator.snapshot().processes().getLast();
        assertEquals(1, secondAdmitted.logicalTimes().arrivalTime());
        assertNotEquals(firstAdmitted.cpuAffinity(), secondAdmitted.cpuAffinity());
    }

    @Test void admittedBeforeStartAndFailedSessionRejectEdits() {
        simulator.initialize(SimulatorConfiguration.defaults());
        submit("fault", 0, "POP BX");
        submit("later", 2, "INC");
        simulator.attemptNextAdmission().orElseThrow();
        assertEquals(SimulatorState.PROGRAM_LOADED, simulator.snapshot().simulatorState());
        assertThrows(IllegalStateException.class, () -> simulator.updateJobArrival(1, 2));
        simulator.start();
        assertThrows(ExecutionEngineException.class, simulator::step);
        assertEquals(SimulatorState.ERROR, simulator.snapshot().simulatorState());
        assertThrows(IllegalStateException.class, () -> simulator.updateJobArrival(2, 0));
    }

    private final SimulatorOrchestrator simulator = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine());
    private ProgramImage image(String name, String... lines) { return new ProgramImage(name, new AsmParser().parse(List.of(lines))); }
    private void submit(String name, long arrival, String... lines) { simulator.submitProgram(image(name, lines), arrival); }
    private long time() { return simulator.snapshot().simulationTimeSeconds().orElseThrow(); }
    private <T> T field(String name, Class<T> type) throws Exception {
        var field = SimulatorOrchestrator.class.getDeclaredField(name); field.setAccessible(true);
        return type.cast(field.get(simulator));
    }

    @Test void futureJobsHaveNoResidenceAndArriveAtBoundaryWithoutExecuting() {
        simulator.initialize(SimulatorConfiguration.defaults());
        var empty = simulator.snapshot();
        submit("future", 2, "INC");
        assertTrue(simulator.attemptNextAdmission().isEmpty()); simulator.start();
        var future = simulator.snapshot();
        assertEquals(empty.memory(), future.memory()); assertTrue(future.processes().isEmpty());
        assertEquals(RuntimeStatus.WAITING_FOR_ARRIVAL, simulator.runtimeStatus());
        assertEquals(OptionalLong.of(2), future.nextArrivalTime());
        assertEquals(new RuntimeStepResult.Idle(RuntimeStatus.WAITING_FOR_ARRIVAL), simulator.step());
        assertEquals(new RuntimeStepResult.Idle(RuntimeStatus.RUNNABLE), simulator.step());
        var arrived = simulator.snapshot();
        assertEquals(2, time()); assertEquals(0, arrived.cpuTicks().orElseThrow());
        assertEquals(LogicalProcessTimes.admitted(2, 2), arrived.processes().getFirst().logicalTimes());
        assertEquals(28, arrived.memory().stream().filter(c -> c.region().equals("KERNEL") && c.content().isPresent()).count());
        assertTrue(arrived.ownerPid().isEmpty());
        simulator.step();
        var done = simulator.completedProcesses().getFirst();
        assertEquals(OptionalLong.of(2), done.logicalTimes().firstDispatchTime());
        assertEquals(OptionalLong.of(3), done.logicalTimes().finishTime());
        assertEquals(1, done.declaredBurst()); assertEquals(1, done.accounting().cpuTicks());
        assertEquals(SimulatorState.FINISHED, simulator.snapshot().simulatorState());
        assertTrue(arrived.processes().getFirst().logicalTimes().finishTime().isEmpty());
        assertEquals(JobState.PENDING, future.jobs().getFirst().state());
        simulator.reset(); simulator.initialize(SimulatorConfiguration.defaults());
        simulator.loadProgram(image("legacy", "INC").instructions()); simulator.start(); simulator.step();
        assertEquals(0, simulator.completedProcesses().getFirst().logicalTimes().arrivalTime());
        assertEquals(OptionalLong.of(1), simulator.completedProcesses().getFirst().logicalTimes().finishTime());
        assertEquals(1, simulator.completedProcesses().size());
    }

    @Test void futureRegistrationDoesNotBlockEligibleAndFcfsOwnerIsNotPreempted() {
        simulator.initialize(SimulatorConfiguration.defaults());
        submit("late", 2, "MOV AX, 30");
        submit("owner", 0, "INC", "INC", "INC");
        submit("early", 1, "MOV AX, 20");
        submit("tie", 2, "MOV AX, 40");
        simulator.start();
        assertEquals(List.of(JobState.PENDING, JobState.ADMITTED, JobState.PENDING, JobState.PENDING),
                simulator.jobs().stream().map(j -> j.state()).toList());
        for (int i = 0; i < 3; i++) assertEquals(1, assertInstanceOf(RuntimeStepResult.Executed.class, simulator.step()).processId());
        assertEquals(List.of(2, 3, 4), simulator.snapshot().readyQueue());
        for (int i = 2; i <= 4; i++) assertEquals(i, assertInstanceOf(RuntimeStepResult.Executed.class, simulator.step()).processId());
        assertEquals(List.of(0L, 1L, 2L, 2L), simulator.completedProcesses().stream().map(p -> p.logicalTimes().arrivalTime()).toList());
        assertEquals(List.of(0L, 3L, 4L, 5L), simulator.completedProcesses().stream().map(p -> p.logicalTimes().firstDispatchTime().orElseThrow()).toList());
        assertEquals(List.of(0, 20, 30, 40), simulator.completedProcesses().stream().map(p -> p.finalContext().ax()).toList());
    }

    @Test void lastCurrentProcessDoesNotFinishWorkloadAndLoadingLifecycleStaysClosed() {
        simulator.initialize(SimulatorConfiguration.defaults()); submit("now", 0, "INC"); submit("later", 3, "INC");
        simulator.start();
        assertThrows(IllegalStateException.class, () -> submit("forbidden", 4, "INC"));
        assertEquals(RuntimeStatus.WAITING_FOR_ARRIVAL, simulator.step().status());
        assertEquals(SimulatorState.RUNNING, simulator.snapshot().simulatorState());
        simulator.pause(); assertThrows(IllegalStateException.class, () -> submit("paused", 4, "INC"));
        simulator.resume(); assertEquals(1, time());
        simulator.step(); simulator.step(); assertEquals(1, simulator.snapshot().cpuTicks().orElseThrow());
        simulator.step(); assertEquals(SimulatorState.FINISHED, simulator.snapshot().simulatorState());
    }

    @Test void capacityReasonCoexistsWithFutureAndEligibleHeadRemainsBlocking() {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 255), 512, 64));
        submit("tooLarge", 0, "INC", "INC"); submit("small", 2, "INC"); simulator.start();
        var before = simulator.snapshot();
        assertEquals(RuntimeStatus.WAITING_FOR_CAPACITY, simulator.runtimeStatus());
        assertEquals(OptionalLong.of(2), before.nextArrivalTime());
        simulator.step(); simulator.step();
        assertEquals(RuntimeStatus.WAITING_FOR_CAPACITY, simulator.runtimeStatus());
        assertTrue(simulator.snapshot().nextArrivalTime().isEmpty());
        assertEquals(before.memory(), simulator.snapshot().memory());
        assertTrue(simulator.snapshot().processes().isEmpty()); assertEquals(0, simulator.snapshot().cpuTicks().orElseThrow());
    }

    @Test void swappingPreservesLogicalMetadataWhileLoopServiceExceedsDeclaredBurst() {
        simulator.initialize(SimulatorConfiguration.defaults()); submit("loop", 0, "INC", "JMP -1", "INT 20H");
        submit("swapped", 1, "INC"); simulator.start(); simulator.step();
        var admitted = simulator.snapshot().processes().getLast().logicalTimes();
        simulator.swapOut(2); assertEquals(admitted, simulator.snapshot().processes().getLast().logicalTimes());
        simulator.swapIn(2); assertEquals(admitted, simulator.snapshot().processes().getLast().logicalTimes());
        for (int i = 0; i < 4; i++) simulator.step();
        var looping = simulator.snapshot().processes().getFirst();
        assertEquals(3, looping.declaredBurst()); assertEquals(5, looping.accounting().cpuTicks());
        assertEquals(OptionalLong.of(0), looping.logicalTimes().firstDispatchTime());
        assertTrue(looping.logicalTimes().finishTime().isEmpty());
    }

    @Test void completedSwappedProcessKeepsTimesAndParsedBurstIncludingSkippedCode() {
        simulator.initialize(SimulatorConfiguration.defaults());
        submit("first", 0, "INC", "INC");
        submit("branch", 1, "JMP 2", "INC", "INT 20H");
        simulator.start(); simulator.step();
        var admitted = simulator.snapshot().processes().getLast().logicalTimes();
        simulator.swapOut(2); simulator.swapIn(2);
        simulator.step(); simulator.step(); simulator.step();
        var completed = simulator.snapshot().completedProcesses().getLast();
        assertEquals(admitted.arrivalTime(), completed.logicalTimes().arrivalTime());
        assertEquals(admitted.admissionTime(), completed.logicalTimes().admissionTime());
        assertEquals(OptionalLong.of(2), completed.logicalTimes().firstDispatchTime());
        assertEquals(OptionalLong.of(4), completed.logicalTimes().finishTime());
        assertEquals(3, completed.declaredBurst()); assertEquals(2, completed.accounting().cpuTicks());
        assertTrue(simulator.snapshot().processes().isEmpty());
        assertTrue(simulator.snapshot().memory().stream().allMatch(cell -> cell.content().isEmpty()));
    }

    @Test void maximumArrivalDoesNotWrapOrExecuteAndOverflowPrecedesAdmission() throws Exception {
        simulator.initialize(SimulatorConfiguration.defaults()); submit("max", Long.MAX_VALUE, "INC"); simulator.start();
        var seconds = SimulationClock.class.getDeclaredField("seconds"); seconds.setAccessible(true);
        seconds.setLong(field("simulationClock", SimulationClock.class), Long.MAX_VALUE);
        var before = simulator.snapshot();
        assertThrows(ExecutionEngineException.class, simulator::step);
        assertEquals(Long.MAX_VALUE, time()); assertEquals(before.memory(), simulator.snapshot().memory());
        assertEquals(before.jobs(), simulator.jobs()); assertTrue(simulator.snapshot().processes().isEmpty());
        assertEquals(0, simulator.snapshot().cpuTicks().orElseThrow());
    }
}
