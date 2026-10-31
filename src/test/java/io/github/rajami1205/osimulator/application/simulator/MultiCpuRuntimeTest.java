package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.cpu.*;
import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.execution.*;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.testing.ControlledClock;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MultiCpuRuntimeTest {
    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5})
    void explicitFcfsMatchesHistoricalConfigurationForEntireWorkload(int count) {
        var legacy = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
        var explicit = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
        var memory = new MemoryConfiguration(1024, count * 140);
        legacy.initialize(new SimulatorConfiguration(memory, 2048, 256, count));
        explicit.initialize(new SimulatorConfiguration(memory, 2048, 256, count,
                io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId.FCFS));
        for (var run : List.of(legacy, explicit)) {
            for (int i = 0; i < count * 2; i++) {
                run.submitProgram(new ProgramImage("p" + i, new AsmParser().parse(List.of("MOV AX, " + i, "INC"))), i < count ? 0 : 3);
            }
            run.start();
        }
        var initial = explicit.snapshot();
        assertEquals(io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId.FCFS,
                initial.configuration().orElseThrow().schedulingPolicy());
        for (int step = 0; step < 5; step++) {
            assertEquals(legacy.snapshot(), explicit.snapshot());
            assertEquals(legacy.step(), explicit.step());
        }
        assertEquals(legacy.snapshot(), explicit.snapshot());
        assertEquals(SimulatorState.FINISHED, explicit.snapshot().simulatorState());
        explicit.reset();
        assertTrue(explicit.snapshot().configuration().isEmpty());
        assertEquals(0, initial.simulationTimeSeconds().orElseThrow());
        assertEquals(count, initial.configuration().orElseThrow().cpuCount());
    }
    private final ControlledClock realClock = new ControlledClock();
    private final SimulatorOrchestrator simulator = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), realClock);
    private void init(int cpus) {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(1024, cpus * 140), 2048, 256, cpus));
    }
    private void submit(long arrival, String... code) {
        simulator.submitProgram(new ProgramImage("p" + simulator.jobs().size(), new AsmParser().parse(List.of(code))), arrival);
    }
    private long time() { return simulator.snapshot().simulationTimeSeconds().orElseThrow(); }
    private static <T> T field(Object object, String name, Class<T> type) {
        try {
            var field = object.getClass().getDeclaredField(name);
            field.setAccessible(true); return type.cast(field.get(object));
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void set(Object object, String name, Object value) {
        try {
            var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private CpuNode node(int id) { return field(simulator, "cpuTopology", CpuTopology.class).node(new CpuId(id)); }
    private ProcessTable table() { return field(simulator, "processTable", ProcessTable.class); }
    private ProcessControlBlock pcb(int pid) { return table().find(pid).orElseThrow(); }
    private List<Long> ticks() { return simulator.snapshot().cpus().stream().map(SimulatorSnapshot.CpuView::cpuTicks).toList(); }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5})
    void oneInstructionPerCpuWithOneGlobalSecondAndImmutableHistory(int count) {
        init(count);
        for (int i = 0; i < count; i++) submit(0, "MOV AX, " + (10 + i), "INC");
        simulator.start();
        var before = simulator.snapshot();
        var result = simulator.step();
        assertEquals(count, result.cpuResults().size());
        assertEquals(1, time());
        for (int i = 0; i < count; i++) {
            var cpu = simulator.snapshot().cpus().get(i);
            assertEquals(i, cpu.id().value()); assertEquals(Optional.of(i + 1), cpu.ownerPid());
            assertEquals(10 + i, cpu.cpu().ax()); assertEquals(1, cpu.cpuTicks());
            assertEquals(4097, cpu.cpu().programCounter());
            assertEquals(OptionalLong.of(0), pcb(i + 1).logicalTimes().firstDispatchTime());
            assertEquals(OptionalInt.of(i), pcb(i + 1).accounting().cpuId());
            assertEquals(RuntimeStepResult.CpuOutcome.EXECUTED, result.cpuResults().get(i).outcome());
            assertEquals(node(i).registers().snapshot(), pcb(i + 1).cpuContext());
        }
        var frozen = simulator.snapshot();
        assertEquals(frozen.cpus().getFirst().cpu(), frozen.cpu().orElseThrow());
        assertThrows(UnsupportedOperationException.class, () -> frozen.cpus().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.lastCpuResults().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.cpus().getFirst().readyQueue().add(99));
        simulator.pause(); realClock.advance(Duration.ofHours(1)); simulator.resume();
        assertEquals(1, time());
        simulator.step();
        assertEquals(2, time()); assertEquals(SimulatorState.FINISHED, simulator.snapshot().simulatorState());
        assertEquals(count, simulator.completedProcesses().size());
        for (var completed : simulator.completedProcesses()) {
            assertEquals(OptionalLong.of(2), completed.logicalTimes().finishTime());
            assertEquals(Duration.ofHours(1), completed.accounting().elapsedTime().orElseThrow());
        }
        assertEquals(0, before.simulationTimeSeconds().orElseThrow());
        assertEquals(1, frozen.cpus().getFirst().cpuTicks());
        var runtime = field(simulator, "runtime", MultiprocessRuntime.class);
        assertEquals(RuntimeStatus.FINISHED, runtime.step().status()); assertEquals(2, time());
        assertEquals(0, table().size());
        assertTrue(field(simulator, "processResources", ProcessResourceRegistry.class).entries().isEmpty());
        simulator.reset(); assertTrue(simulator.snapshot().cpus().isEmpty());
    }

    @Test void localFcfsDoesNotPreemptOrExecuteSuccessorWithinInterval() {
        init(2);
        submit(0, "MOV AX, 11", "INC", "INC");
        submit(0, "MOV BX, 22");
        submit(0, "MOV AX, 33");
        submit(0, "MOV BX, 44");
        simulator.start();
        assertEquals(List.of(1, 3), node(0).readyQueue().entries());
        assertEquals(List.of(2, 4), node(1).readyQueue().entries());
        simulator.step();
        assertEquals(List.of(1L, 1L), ticks());
        assertEquals(OptionalLong.empty(), pcb(4).logicalTimes().firstDispatchTime());
        assertEquals(List.of(4), node(1).readyQueue().entries());
        simulator.step();
        assertEquals(List.of(2L, 2L), ticks());
        assertEquals(11, pcb(1).cpuContext().ax());
        assertEquals(0, pcb(1).cpuContext().bx());
        assertEquals(OptionalLong.empty(), pcb(3).logicalTimes().firstDispatchTime());
        simulator.step(); assertEquals(List.of(3L, 2L), ticks());
        simulator.step(); assertEquals(List.of(4L, 2L), ticks());
        assertEquals(List.of(2, 4, 1, 3), simulator.completedProcesses().stream().map(CompletedProcessRecord::processId).toList());
        assertEquals(4, time());
    }

    @Test void sharedOutputUsesCpuIdOrder() {
        init(3);
        for (int i = 0; i < 3; i++) submit(0, "MOV DX, " + (9 - i), "INT 10H");
        simulator.start(); simulator.step(); simulator.step();
        assertEquals(List.of(9, 8, 7), simulator.screenOutput());
        assertEquals(2, time()); assertEquals(List.of(2L, 2L, 2L), ticks());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sharedFilesystemObservesCpuOrderAndPreservesCommittedCreate(boolean conflict) {
        init(2);
        submit(0, "MOV DX, \"shared\"", "MOV AH, 3CH", "INT 21H");
        submit(0, "MOV DX, \"shared\"", conflict ? "MOV AH, 3CH" : "MOV AH, 3DH", "INT 21H");
        simulator.start(); simulator.step(); simulator.step();
        if (conflict) assertThrows(ExecutionEngineException.class, simulator::step);
        else simulator.step();
        assertEquals(3, time());
        assertEquals(List.of(3L, conflict ? 2L : 3L), ticks());
        assertEquals("", field(simulator, "secondaryStorage",
                io.github.rajami1205.osimulator.model.storage.SecondaryStorage.class).readUserFile("shared"));
        assertEquals(conflict ? SimulatorState.ERROR : SimulatorState.FINISHED, simulator.snapshot().simulatorState());
        assertEquals(OptionalLong.of(3), simulator.completedProcesses().getFirst().logicalTimes().finishTime());
    }

    @Test void translationErrorOnCpuOneDoesNotReplaceCpuZeroView() {
        init(2); submit(0, "INC", "INC"); submit(0, "INC", "INC"); simulator.start(); simulator.step();
        var before = simulator.snapshot();
        var resource = field(simulator, "processResources", ProcessResourceRegistry.class).find(2).orElseThrow();
        field(simulator, "memory", MainMemory.class).release(((UserImageResidence.Resident) resource.residence()).allocation());
        var after = simulator.snapshot();
        assertEquals(SimulatorState.ERROR, after.simulatorState());
        assertEquals(before.cpus().getFirst(), after.cpus().getFirst());
        assertEquals(before.nextInstructionPhysicalAddress(), after.nextInstructionPhysicalAddress());
        assertTrue(after.addressTranslationError().isEmpty());
        assertTrue(after.cpus().get(1).addressTranslationError().isPresent());
        assertTrue(after.cpus().get(1).nextInstructionPhysicalAddress().isEmpty());
        assertEquals(after, simulator.snapshot());
    }

    @Test void futureArrivalsAndBoundaryAdmissionNeverExecuteEarly() {
        init(2);
        submit(0, "INC"); submit(2, "MOV AX, 7"); submit(2, "MOV AX, 8");
        simulator.start(); simulator.step();
        assertEquals(RuntimeStatus.WAITING_FOR_ARRIVAL, simulator.runtimeStatus());
        assertEquals(List.of(1L, 0L), ticks());
        var idle = simulator.step();
        assertTrue(idle.cpuResults().stream().allMatch(r -> r.outcome() == RuntimeStepResult.CpuOutcome.IDLE));
        assertEquals(2, time()); assertEquals(List.of(1L, 0L), ticks());
        assertEquals(OptionalLong.of(2), pcb(2).logicalTimes().admissionTime());
        assertTrue(pcb(2).logicalTimes().firstDispatchTime().isEmpty());
        simulator.step();
        assertEquals(List.of(2L, 1L), ticks());
        for (var p : simulator.completedProcesses().subList(1, 3)) {
            assertEquals(OptionalLong.of(2), p.logicalTimes().firstDispatchTime());
            assertEquals(OptionalLong.of(3), p.logicalTimes().finishTime());
        }
    }

    @Test void capacityReleasedBySeveralFinishesAdmitsOnlyAtNextBoundary() {
        init(2);
        for (int i = 0; i < 12; i++) submit(0, "INC");
        simulator.start(); assertEquals(10, table().size());
        simulator.step();
        assertEquals(10, table().size()); assertEquals(2, simulator.completedProcesses().size());
        assertEquals(OptionalLong.of(1), pcb(11).logicalTimes().admissionTime());
        assertEquals(new CpuId(0), pcb(11).cpuAffinity());
        assertEquals(new CpuId(1), pcb(12).cpuAffinity());
        assertTrue(pcb(11).logicalTimes().firstDispatchTime().isEmpty());
        assertEquals(0, pcb(12).accounting().cpuTicks());
        for (int i = 0; i < 5; i++) simulator.step();
        assertEquals(6, time()); assertEquals(12, simulator.completedProcesses().size());
    }

    @Test void globalCapacityIdleOnlyAdvancesGlobalClock() {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(281, 280), 2048, 256, 2));
        submit(0, "INC", "INC"); simulator.start();
        var before = simulator.snapshot();
        assertEquals(RuntimeStatus.WAITING_FOR_CAPACITY, simulator.step().status());
        assertEquals(1, time()); assertEquals(List.of(0L, 0L), ticks());
        assertEquals(before.cpus(), simulator.snapshot().cpus());
    }

    @ParameterizedTest @ValueSource(strings = {"clock", "ticks", "accounting", "affinityHistory"})
    void allParticipantPrevalidationPreventsPartialExecution(String fault) {
        init(3); for (int i = 0; i < 3; i++) submit(0, "INC", "INC");
        simulator.start();
        switch (fault) {
            case "clock" -> set(field(simulator, "simulationClock", SimulationClock.class), "seconds", Long.MAX_VALUE);
            case "ticks" -> set(node(2).tickCounter(), "ticks", Long.MAX_VALUE);
            case "accounting" -> pcb(3).replaceAccounting(new ProcessAccounting(OptionalInt.of(2),
                    Optional.empty(), Long.MAX_VALUE, Optional.empty()));
            case "affinityHistory" -> pcb(3).replaceAccounting(new ProcessAccounting(OptionalInt.of(1),
                    Optional.empty(), 0, Optional.empty()));
        }
        assertThrows(ExecutionEngineException.class, simulator::step);
        assertEquals(fault.equals("clock") ? Long.MAX_VALUE : 0, time());
        assertEquals(0, node(0).tickCounter().current()); assertEquals(0, node(1).tickCounter().current());
        assertEquals(0, node(0).registers().accumulator()); assertEquals(0, node(1).registers().accumulator());
        assertEquals(SimulatorState.ERROR, simulator.snapshot().simulatorState());
    }

    @ParameterizedTest @ValueSource(ints = {0, 1})
    void fatalFailureStopsLaterCpusAndPreservesExactlyOnePartialCommit(int failingCpu) {
        init(3);
        for (int i = 0; i < 3; i++) submit(0, i == failingCpu ? "POP AX" : "INC");
        simulator.start();
        assertThrows(ExecutionEngineException.class, simulator::step);
        assertEquals(failingCpu == 0 ? 0 : 1, time());
        assertEquals(failingCpu == 0 ? List.of(0L, 0L, 0L) : List.of(1L, 0L, 0L), ticks());
        var outcomes = simulator.snapshot().lastCpuResults();
        assertEquals(RuntimeStepResult.CpuOutcome.FAILED, outcomes.get(failingCpu).outcome());
        assertEquals(RuntimeStepResult.CpuOutcome.NOT_EXECUTED, outcomes.get(2).outcome());
        assertTrue(outcomes.get(2).tickResult().isEmpty());
        if (failingCpu == 1) {
            assertEquals(1, simulator.completedProcesses().size());
            assertEquals(OptionalLong.of(1), simulator.completedProcesses().getFirst().logicalTimes().finishTime());
        }
        var errorSnapshot = simulator.snapshot();
        assertThrows(IllegalStateException.class, simulator::step);
        assertEquals(errorSnapshot, simulator.snapshot());
    }

    @Test void cleanupFailureAfterAllExecutionPreservesBothTicksAndFinishes() {
        init(2); submit(0, "INC"); submit(0, "INC"); simulator.start();
        pcb(1).setNextPcbAddress(Optional.of(new PcbAddress(9)));
        assertThrows(ExecutionEngineException.class, simulator::step);
        assertEquals(1, time()); assertEquals(List.of(1L, 1L), ticks());
        assertEquals(OptionalLong.of(1), pcb(1).logicalTimes().finishTime());
        assertEquals(OptionalLong.of(1), pcb(2).logicalTimes().finishTime());
        assertTrue(simulator.snapshot().lastCpuResults().stream().allMatch(r -> r.outcome() == RuntimeStepResult.CpuOutcome.EXECUTED));
    }

    @Test void secondaryCleanupErrorDoesNotHideOriginalExecutionFailure() {
        init(3); submit(0, "INC"); submit(0, "POP AX"); submit(0, "INC"); simulator.start();
        pcb(1).setNextPcbAddress(Optional.of(new PcbAddress(9)));
        var error = assertThrows(ExecutionEngineException.class, simulator::step);
        assertTrue(error.getMessage().contains("stack"));
        assertEquals(1, error.getSuppressed().length);
        assertEquals(1, time()); assertEquals(List.of(1L, 0L, 0L), ticks());
        assertEquals(OptionalLong.of(1), pcb(1).logicalTimes().finishTime());
    }

    @Test void suspendedQueuesRecoverIndependentlyAndKernelAffinitySurvivesExecution() {
        init(2); submit(0, "INC", "INC"); submit(0, "INC", "INC");
        simulator.attemptNextAdmission(); simulator.attemptNextAdmission();
        simulator.swapOut(1); simulator.swapOut(2);
        assertEquals(List.of(1), node(0).suspendedReadyQueue().entries());
        assertEquals(List.of(2), node(1).suspendedReadyQueue().entries());
        simulator.start();
        assertTrue(node(0).suspendedReadyQueue().entries().isEmpty());
        assertTrue(node(1).suspendedReadyQueue().entries().isEmpty());
        simulator.step();
        for (int i = 0; i < 2; i++) {
            int pid = i + 1;
            assertThrows(IllegalStateException.class, () -> simulator.swapOut(pid));
            var resource = field(simulator, "processResources", ProcessResourceRegistry.class).find(pid).orElseThrow();
            var memory = field(simulator, "memory", MainMemory.class);
            var content = assertInstanceOf(PcbContent.class, memory.read(resource.kernel().base() + 16));
            assertEquals(new PcbValue.Number(i), content.value());
        }
        simulator.step(); assertEquals(2, simulator.completedProcesses().size());
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "wrongAffinity", "context", "foreignQueue"})
    void corruptedLocalOwnershipFailsBeforeAnyFurtherWork(String corruption) {
        init(2); submit(0, "INC", "INC"); submit(0, "INC", "INC"); simulator.start(); simulator.step();
        switch (corruption) {
            case "duplicate" -> set(node(1).dispatcher(), "owner", pcb(1));
            case "wrongAffinity" -> set(pcb(2), "cpuAffinity", CpuId.ZERO);
            case "context" -> node(1).registers().setProgramCounter(4096);
            case "foreignQueue" -> {
                @SuppressWarnings("unchecked") var queue = (Set<Integer>) field(node(1).readyQueue(), "processes", Set.class);
                queue.add(1);
            }
        }
        assertThrows(ExecutionEngineException.class, simulator::step);
        assertEquals(1, time()); assertEquals(List.of(1L, 1L), ticks());
    }
}
