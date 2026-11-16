package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.cpu.CpuTopology;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.execution.*;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import io.github.rajami1205.osimulator.testing.ControlledClock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DemandPagingSessionTest {
    @Test void residentCpuAndFaultingCpuShareExactlyOneInterval() {
        var run = run(2, 3);
        submit(run, "resident", "INC", "INC", "INC");
        submit(run, "branch", "JMP 4", "INC", "INC", "INC", "INC", "INT 20H");
        run.start(); run.step(); run.step(); var s = run.snapshot();
        assertEquals(2, s.simulationTimeSeconds().orElseThrow());
        assertEquals(List.of(2L, 2L), s.cpus().stream().map(SimulatorSnapshot.CpuView::cpuTicks).toList());
        assertEquals(3, s.paging().events().stream().filter(e -> e.kind() == PageFaultEvent.Kind.FAULT_OBSERVED).count());
        assertEquals(2, s.cpus().getFirst().cpu().accumulator()); assertEquals(1, s.cpus().get(1).cpu().accumulator());
        run.step(); assertEquals(SimulatorState.FINISHED, run.snapshot().simulatorState());
    }
    @Test void completionReleasesBackingAndReconsidersWaitingAdmission() {
        var run = run(1, 1); submit(run, "small", "INC");
        var longProgram = new ArrayList<String>(); longProgram.add("INT 20H");
        for (int i = 1; i < 256; i++) longProgram.add("INC");
        run.submitProgram(new ProgramImage("large", new AsmParser().parse(longProgram)));
        run.start(); assertEquals(1, run.snapshot().processes().size());
        run.step(); assertEquals(1, run.snapshot().processes().size());
        assertEquals(256, run.snapshot().processes().getFirst().limit());
        assertEquals(1, run.snapshot().simulationTimeSeconds().orElseThrow());
        run.step(); assertEquals(SimulatorState.FINISHED, run.snapshot().simulatorState());
        assertEquals(2, run.snapshot().simulationTimeSeconds().orElseThrow());
    }
    @Test void clockOverflowPreventsPageInAndAllInstructionEffects() throws Exception {
        var run = run(1, 1); submit(run, "p", "INC"); run.start();
        var clock = field(run, "simulationClock", SimulationClock.class);
        var seconds = SimulationClock.class.getDeclaredField("seconds"); seconds.setAccessible(true); seconds.setLong(clock, Long.MAX_VALUE);
        assertThrows(ExecutionEngineException.class, run::step);
        assertEquals(Long.MAX_VALUE, run.snapshot().simulationTimeSeconds().orElseThrow());
        assertTrue(run.snapshot().paging().events().isEmpty()); assertTrue(demand(run, 1).table().frames().isEmpty());
    }
    @Test void readyVictimCanBeEvictedThenSuspendedWithoutExtraExecution() throws Exception {
        var run = run(1, 1); submit(run, "waiting", "INC", "INC"); submit(run, "resident", "INC"); run.start();
        var memory = field(run, "memory", MainMemory.class); var storage = field(run, "secondaryStorage", SecondaryStorage.class);
        var registry = field(run, "processResources", ProcessResourceRegistry.class); var processes = field(run, "processTable", ProcessTable.class);
        var pageService = new PageInService(memory, storage, registry); var second = processes.find(2).orElseThrow();
        pageService.resolve(second, pageService.request(second));
        run.step(); assertEquals(ProcessState.RUNNING, processes.find(1).orElseThrow().state());
        assertThrows(IllegalStateException.class, () -> run.swapOut(1));
        assertEquals(SimulatorState.RUNNING, run.snapshot().simulatorState());
        var before = run.snapshot().simulationTimeSeconds();
        run.swapOut(2); var woke = run.snapshot();
        assertEquals(before, woke.simulationTimeSeconds()); assertEquals(1, woke.cpus().getFirst().cpuTicks());
        assertTrue(woke.paging().pending().isEmpty()); assertTrue(woke.readyQueue().isEmpty());
        assertEquals(1, woke.paging().events().stream().filter(e -> e.kind() == PageFaultEvent.Kind.EVICTION_COMMITTED).count());
        assertEquals(ProcessState.READY_SUSPENDED, second.state());
        run.step(); run.step(); assertEquals(SimulatorState.FINISHED, run.snapshot().simulatorState());
    }
    static <T> T field(Object object, String name, Class<T> type) throws Exception {
        var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(object));
    }
    SimulatorOrchestrator run(int cpus, int frames) {
        var run = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
        int kernel = Math.max(cpus * 140, 256 - frames * 4);
        run.initialize(new SimulatorConfiguration(new MemoryConfiguration(kernel + frames * 4, kernel), 2048, 256,
                cpus, SchedulingPolicyId.FCFS, UserAllocationStrategy.FIRST_FIT, MemoryMode.PAGING, Optional.empty(),
                Optional.of(new PagingConfiguration(4, PagingLoadMode.DEMAND))));
        return run;
    }
    void submit(SimulatorOrchestrator run, String name, String... lines) { run.submitProgram(new ProgramImage(name, new AsmParser().parse(List.of(lines)))); }
    UserImageResidence.Demand demand(SimulatorOrchestrator run, int pid) throws Exception {
        return (UserImageResidence.Demand) field(run, "processResources", ProcessResourceRegistry.class).find(pid).orElseThrow().residence();
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5})
    void simultaneousFaultsUseOneGlobalSecondAndOneInstructionPerCpu(int count) throws Exception {
        var run = run(count, count * 2);
        for (int i = 0; i < count; i++) submit(run, "p" + i, "INC", "INC");
        run.start(); var initial = run.snapshot();
        assertEquals(count, initial.paging().pageTables().size());
        assertTrue(initial.paging().frames().stream().noneMatch(SimulatorSnapshot.FrameView::occupied));
        for (int i = 0; i < 3; i++) assertEquals(initial, run.snapshot());
        run.step(); var one = run.snapshot();
        assertEquals(1, one.simulationTimeSeconds().orElseThrow());
        assertTrue(one.cpus().stream().allMatch(c -> c.cpuTicks() == 1 && c.cpu().accumulator() == 1));
        assertEquals(count * 2, one.paging().events().size());
        assertEquals(java.util.stream.IntStream.range(0, count).boxed().toList(), one.paging().events().stream()
                .filter(e -> e.kind() == PageFaultEvent.Kind.FAULT_OBSERVED).map(e -> e.cpuId().value()).toList());
        assertTrue(one.cpus().stream().allMatch(c -> c.nextInstructionPhysicalAddress().isPresent()));
        assertTrue(initial.paging().pageTables().stream().allMatch(t -> t.pages().stream().allMatch(p -> p.frame().isEmpty())));
        assertThrows(UnsupportedOperationException.class, () -> one.paging().events().clear());
        run.step(); var done = run.snapshot();
        assertEquals(SimulatorState.FINISHED, done.simulatorState()); assertEquals(2, done.simulationTimeSeconds().orElseThrow());
        assertTrue(done.paging().frames().stream().noneMatch(SimulatorSnapshot.FrameView::occupied));
        assertTrue(done.paging().pageTables().isEmpty()); assertTrue(done.paging().pending().isEmpty());
        var storage = field(run, "secondaryStorage", SecondaryStorage.class); var all = storage.allocateSwap(256); storage.releaseSwap(all);
    }
    @Test void freeFramePriorityBlockedRecoveryAndNoSuccessorInstructionInSameInterval() throws Exception {
        // CPU0's completed image is protected until post-interval cleanup: a real no-victim window.
        var run = run(2, 1); submit(run, "first", "INC"); submit(run, "second", "INC", "INC"); run.start();
        run.step(); var blocked = run.snapshot();
        assertEquals(List.of(1L, 0L), blocked.cpus().stream().map(SimulatorSnapshot.CpuView::cpuTicks).toList());
        assertEquals(RuntimeStepResult.CpuOutcome.WAITING_FOR_PAGE, blocked.lastCpuResults().get(1).outcome());
        assertTrue(blocked.paging().pending().isEmpty());
        assertEquals(ProcessState.READY, blocked.processes().getFirst().state());
        assertTrue(blocked.cpus().get(1).ownerPid().isEmpty()); assertTrue(blocked.cpus().get(1).cpu().instructionRegister().isEmpty());
        run.step(); var ready = run.snapshot();
        assertEquals(List.of(1L, 1L), ready.cpus().stream().map(SimulatorSnapshot.CpuView::cpuTicks).toList());
        assertTrue(ready.paging().pending().isEmpty()); assertTrue(ready.cpus().get(1).readyQueue().isEmpty());
        assertEquals(1, ready.cpus().get(1).cpu().accumulator()); assertEquals(2, ready.simulationTimeSeconds().orElseThrow());
        run.step(); assertEquals(SimulatorState.FINISHED, run.snapshot().simulatorState());
        assertEquals(3, run.snapshot().simulationTimeSeconds().orElseThrow());
        assertEquals(2, run.snapshot().paging().events().stream().filter(e -> e.kind() == PageFaultEvent.Kind.FAULT_OBSERVED).count());
    }
    @Test void branchFaultUsesCurrentVersionAndSnapshotNeverLoadsMissingPage() {
        var run = run(1, 2); submit(run, "branch", "JMP 4", "INC", "INC", "INC", "INC", "INT 20H"); run.start();
        run.step(); var absent = run.snapshot();
        assertEquals(4100, absent.cpus().getFirst().cpu().programCounter());
        assertTrue(absent.cpus().getFirst().nextInstructionPhysicalAddress().isEmpty());
        assertTrue(absent.cpus().getFirst().addressTranslationError().isEmpty());
        assertEquals(2, absent.program().stream().filter(p -> p.physicalAddress().isEmpty()).count());
        for (int i = 0; i < 4; i++) assertEquals(absent, run.snapshot());
        run.step(); var next = run.snapshot();
        assertEquals(2, next.simulationTimeSeconds().orElseThrow()); assertEquals(2, next.cpus().getFirst().cpuTicks());
        assertEquals(2, next.paging().pageTables().getFirst().revision());
        assertTrue(next.cpus().getFirst().nextInstructionPhysicalAddress().isPresent());
        run.step(); assertEquals(SimulatorState.FINISHED, run.snapshot().simulatorState());
    }
    @Test void morePagesThanFramesProgressWithoutRepeatedFaultsOrArtificialTime() throws Exception {
        var run = run(1, 1); submit(run, "larger", "INC", "INC", "INC", "INC", "INC"); run.start();
        for (int i = 0; i < 4; i++) run.step(); var before = run.snapshot();
        var memory = field(run, "memory", MainMemory.class); var storage = field(run, "secondaryStorage", SecondaryStorage.class);
        var registry = field(run, "processResources", ProcessResourceRegistry.class); var old = demand(run, 1);
        run.step(); var done = run.snapshot();
        assertEquals(Optional.of(RuntimeStatus.FINISHED), done.runtimeStatus());
        assertEquals(SimulatorState.FINISHED, done.simulatorState()); assertEquals(5, done.simulationTimeSeconds().orElseThrow());
        assertEquals(5, done.cpus().getFirst().cpuTicks()); assertEquals(4, before.cpus().getFirst().cpuTicks());
        assertTrue(done.paging().pending().isEmpty());
        assertEquals(2, done.paging().events().stream().filter(e -> e.kind() == PageFaultEvent.Kind.FAULT_OBSERVED).count());
        assertEquals(1, done.paging().events().stream().filter(e -> e.kind() == PageFaultEvent.Kind.EVICTION_COMMITTED).count());
        for (int i = 0; i < 3; i++) assertEquals(done, run.snapshot());
        assertTrue(memory.clockView().orElseThrow().references().isEmpty());
        run.reset(); assertTrue(registry.pendingPages().isEmpty());
        assertThrows(RuntimeException.class, () -> memory.validatePageTable(old.table()));
        assertThrows(RuntimeException.class, () -> storage.readSwapBlock(old.backing().allocation()));
        assertTrue(run.snapshot().paging().events().isEmpty());
        assertEquals(0, memory.clockView().orElseThrow().hand());
    }
    @ParameterizedTest @ValueSource(ints = {0, 1})
    void successfulPageInSurvivesInstructionFailureButDoesNotCommitTimeAlone(int failingCpu) throws Exception {
        var run = run(3, 3);
        for (int i = 0; i < 3; i++) submit(run, "p" + i, i == failingCpu ? "POP AX" : "INC");
        run.start(); assertThrows(ExecutionEngineException.class, run::step); var s = run.snapshot();
        assertEquals(failingCpu, s.simulationTimeSeconds().orElseThrow());
        assertEquals(failingCpu == 0 ? List.of(0L, 0L, 0L) : List.of(1L, 0L, 0L), s.cpus().stream().map(SimulatorSnapshot.CpuView::cpuTicks).toList());
        assertEquals(1, demand(run, failingCpu + 1).table().frames().size());
        assertEquals(RuntimeStepResult.CpuOutcome.NOT_EXECUTED, s.lastCpuResults().get(2).outcome());
        assertEquals(SimulatorState.ERROR, s.simulatorState());
    }
    @Test void invalidBackingOnLaterCpuFailsStructuralPrevalidationBeforeAnyPageIn() throws Exception {
        var run = run(2, 2); submit(run, "a", "INC"); submit(run, "b", "INC"); run.start();
        field(run, "secondaryStorage", SecondaryStorage.class).releaseSwap(demand(run, 2).backing().allocation());
        assertThrows(ExecutionEngineException.class, run::step); var s = run.snapshot();
        assertEquals(0, s.simulationTimeSeconds().orElseThrow()); assertTrue(s.paging().events().isEmpty());
        assertTrue(s.cpus().stream().allMatch(c -> c.cpuTicks() == 0));
    }
    @Test void blockedWithoutRequestStillFailsAndDoesNotCreateAnUnblockingMechanism() throws Exception {
        var run = run(1, 1); submit(run, "a", "INC"); run.start();
        var pcb = field(run, "processTable", ProcessTable.class).find(1).orElseThrow();
        field(run, "cpuTopology", CpuTopology.class).node(CpuId.ZERO).readyQueue().remove(1); pcb.changeState(ProcessState.BLOCKED);
        assertThrows(ExecutionEngineException.class, run::step);
        assertEquals(SimulatorState.ERROR, run.snapshot().simulatorState());
    }
    @Test void futureArrivalsStillConsumeIdleSecondsWithoutFaultsOrCpuTicks() {
        var run = run(1, 1); run.submitProgram(new ProgramImage("future", new AsmParser().parse(List.of("INC"))), 2); run.start();
        run.step(); run.step(); var idle = run.snapshot();
        assertEquals(2, idle.simulationTimeSeconds().orElseThrow()); assertEquals(0, idle.cpus().getFirst().cpuTicks());
        assertTrue(idle.paging().events().isEmpty());
        run.step(); assertEquals(3, run.snapshot().simulationTimeSeconds().orElseThrow()); assertEquals(SimulatorState.FINISHED, run.snapshot().simulatorState());
    }
}
