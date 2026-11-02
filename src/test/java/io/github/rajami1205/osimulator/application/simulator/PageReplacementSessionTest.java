package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.cpu.CpuTopology;
import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.rajami1205.osimulator.application.simulator.DemandPagingSessionTest.field;

class PageReplacementSessionTest {
    final DemandPagingSessionTest f = new DemandPagingSessionTest();
    PageInService pages(SimulatorOrchestrator run) throws Exception {
        return new PageInService(field(run, "memory", MainMemory.class), field(run, "secondaryStorage", SecondaryStorage.class),
                field(run, "processResources", ProcessResourceRegistry.class));
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5})
    void oneFrameSupportsSuccessiveCpuTurnsWithoutExtraTime(int count) throws Exception {
        var run = f.run(count, 1);
        for (int i = 0; i < count; i++) f.submit(run, "p" + i, "INC", "INC", "INC");
        run.start();
        for (int time = 1; time <= 2; time++) {
            run.step(); var snapshot = run.snapshot();
            assertEquals(time, snapshot.simulationTimeSeconds().orElseThrow());
            for (var cpu : snapshot.cpus()) {
                assertEquals(time, cpu.cpuTicks()); assertEquals(time, cpu.cpu().accumulator());
                assertEquals(4096 + time, cpu.cpu().programCounter());
                assertEquals(RuntimeStepResult.CpuOutcome.EXECUTED, snapshot.lastCpuResults().get(cpu.id().value()).outcome());
            }
            assertTrue(snapshot.paging().pending().isEmpty());
            assertEquals(count - 1, snapshot.cpus().stream().filter(c -> c.nextInstructionPhysicalAddress().isEmpty()).count());
            var memory = field(run, "memory", MainMemory.class); var before = memory.clockView();
            for (int i = 0; i < 4; i++) assertEquals(snapshot, run.snapshot());
            assertEquals(before, memory.clockView());
        }
        var faults = run.snapshot().paging().events().stream().filter(e -> e.kind() == PageFaultEvent.Kind.FAULT_OBSERVED).toList();
        assertEquals(count == 1 ? 1 : count * 2, faults.size());
        assertEquals(java.util.stream.IntStream.range(0, faults.size()).map(i -> i % count).boxed().toList(), faults.stream().map(e -> e.cpuId().value()).toList());
        for (int i = 0; i < count + 1 && run.snapshot().simulatorState() != SimulatorState.FINISHED; i++) run.step();
        assertEquals(SimulatorState.FINISHED, run.snapshot().simulatorState());
        assertTrue(run.snapshot().cpus().stream().allMatch(c -> c.cpuTicks() == 3));
    }
    @Test void laterCpuRefreshesTableCapturedBeforeEarlierCpuEvictedIt() throws Exception {
        var run = f.run(2, 1); f.submit(run, "a", "INC", "INC"); f.submit(run, "b", "INC", "INC"); run.start();
        var pcb = field(run, "processTable", ProcessTable.class).find(2).orElseThrow();
        var service = pages(run); service.resolve(pcb, service.request(pcb)); var old = f.demand(run, 2).table();
        run.step(); var snapshot = run.snapshot();
        assertTrue(snapshot.cpus().stream().allMatch(c -> c.cpuTicks() == 1));
        assertEquals(old.revision() + 2, f.demand(run, 2).table().revision());
        assertEquals(List.of(0, 1), snapshot.paging().events().stream().filter(e -> e.kind() == PageFaultEvent.Kind.EVICTION_COMMITTED).map(e -> e.cpuId().value()).toList());
        assertThrows(RuntimeException.class, () -> field(run, "memory", MainMemory.class).validatePageTable(old));
    }
    @ParameterizedTest @ValueSource(ints = {0, 1})
    void committedEvictionAndFetchSurviveSemanticFailureWithOriginalTemporalCommit(int failing) throws Exception {
        var run = f.run(3, 1);
        for (int i = 0; i < 3; i++) f.submit(run, "p" + i, i == failing ? "POP AX" : "INC", "INC");
        run.start(); var pcb = field(run, "processTable", ProcessTable.class).find(3).orElseThrow();
        var service = pages(run); service.resolve(pcb, service.request(pcb));
        assertThrows(ExecutionEngineException.class, run::step); var snapshot = run.snapshot();
        assertEquals(failing, snapshot.simulationTimeSeconds().orElseThrow());
        assertEquals(failing == 0 ? List.of(0L, 0L, 0L) : List.of(1L, 0L, 0L), snapshot.cpus().stream().map(SimulatorSnapshot.CpuView::cpuTicks).toList());
        assertEquals(failing + 1, snapshot.paging().events().stream().filter(e -> e.kind() == PageFaultEvent.Kind.EVICTION_COMMITTED).count());
        assertTrue(snapshot.paging().frames().getFirst().referenced().orElseThrow());
        assertEquals(failing + 1, snapshot.paging().frames().getFirst().ownerPid().orElseThrow());
        assertEquals(RuntimeStepResult.CpuOutcome.NOT_EXECUTED, snapshot.lastCpuResults().get(2).outcome());
    }
    @Test void pendingBatchUsesClockInCpuOrderAndAwakenedEvictedPageFaultsLegitimately() throws Exception {
        var run = f.run(3, 1); f.submit(run, "finishing", "INC");
        f.submit(run, "second", "INC", "INC", "INC"); f.submit(run, "third", "INC", "INC", "INC"); run.start();
        run.step(); var recovered = run.snapshot();
        assertEquals(List.of(1L, 0L, 0L), recovered.cpus().stream().map(SimulatorSnapshot.CpuView::cpuTicks).toList());
        assertEquals(RuntimeStepResult.CpuOutcome.WAITING_FOR_PAGE, recovered.lastCpuResults().get(1).outcome());
        assertEquals(RuntimeStepResult.CpuOutcome.WAITING_FOR_PAGE, recovered.lastCpuResults().get(2).outcome());
        assertTrue(recovered.paging().pending().isEmpty());
        assertEquals(List.of(2), recovered.cpus().get(1).readyQueue()); assertEquals(List.of(3), recovered.cpus().get(2).readyQueue());
        assertEquals(3, recovered.paging().frames().getFirst().ownerPid().orElseThrow());
        assertEquals(List.of(1, 2), recovered.paging().events().stream().filter(e -> e.time() == 1 && e.kind() == PageFaultEvent.Kind.PAGE_IN_COMMITTED).map(e -> e.cpuId().value()).toList());
        assertEquals(Optional.of(RuntimeStatus.RUNNABLE), recovered.runtimeStatus());
        run.step(); var next = run.snapshot();
        assertEquals(List.of(1L, 1L, 1L), next.cpus().stream().map(SimulatorSnapshot.CpuView::cpuTicks).toList());
        assertTrue(next.paging().pending().isEmpty()); assertEquals(2, next.simulationTimeSeconds().orElseThrow());
        assertEquals(List.of(1, 2), next.paging().events().stream().filter(e -> e.time() == 1 && e.kind() == PageFaultEvent.Kind.FAULT_OBSERVED).map(e -> e.cpuId().value()).toList());
        var topology = field(run, "cpuTopology", CpuTopology.class);
        assertTrue(topology.node(new CpuId(1)).readyQueue().entries().isEmpty());
        assertTrue(topology.node(new CpuId(2)).readyQueue().entries().isEmpty());
    }
}
