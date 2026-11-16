package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.instruction.IncInstruction;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId;
import io.github.rajami1205.osimulator.testing.ControlledClock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PagingSessionTest {
    private <T> T field(Object object, String name, Class<T> type) throws Exception {
        var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(object));
    }
    private void submit(SimulatorOrchestrator run, String name, String... lines) {
        run.submitProgram(new ProgramImage(name, new AsmParser().parse(List.of(lines))));
    }
    @Test void branchAcrossScatteredPagesUsesRealPhysicalPcAndBothAddressOrders() throws Exception {
        var run = simulator(1); var memory = field(run, "memory", MainMemory.class);
        var a = memory.reserveImage(90, 4); memory.confirmUser(a);
        var b = memory.reserveImage(91, 4); memory.confirmUser(b); memory.release(a.image());
        submit(run, "branch", "JMP 4", "INC", "INC", "INC", "INC", "INT 20H");
        run.start(); run.step(); var snapshot = run.snapshot();
        assertEquals(4100, snapshot.cpus().getFirst().cpu().programCounter());
        assertEquals(Optional.of(148), snapshot.cpus().getFirst().nextInstructionPhysicalAddress());
        assertEquals(List.of(140, 141, 142, 143, 148, 149), snapshot.program().stream().map(SimulatorSnapshot.ProgramEntry::address).toList());
        assertEquals(List.of(4096, 4097, 4098, 4099, 4100, 4101), snapshot.program().stream().map(p -> p.logicalAddress().orElseThrow()).toList());
        assertEquals(List.of(0, 2), snapshot.paging().pageTables().getFirst().pages().stream().map(SimulatorSnapshot.PageMapping::frameId).toList());
        run.step(); run.step(); assertEquals(SimulatorState.FINISHED, run.snapshot().simulatorState());
        assertEquals(3, run.snapshot().simulationTimeSeconds().orElseThrow());
        assertEquals(3, run.snapshot().cpus().getFirst().cpuTicks());
        assertEquals(1, memory.frames().stream().filter(f -> f.handle().isPresent()).count());
        memory.release(b.image());
    }
    @ParameterizedTest @ValueSource(ints = {0, 1})
    void fatalInstructionPreservesGlobalPartialCommit(int failingCpu) {
        var run = simulator(3);
        for (int i = 0; i < 3; i++) submit(run, "p" + i, i == failingCpu ? "POP AX" : "INC");
        run.start(); assertThrows(ExecutionEngineException.class, run::step);
        var s = run.snapshot(); assertEquals(SimulatorState.ERROR, s.simulatorState());
        assertEquals(failingCpu, s.simulationTimeSeconds().orElseThrow());
        assertEquals(failingCpu == 0 ? List.of(0L, 0L, 0L) : List.of(1L, 0L, 0L),
                s.cpus().stream().map(SimulatorSnapshot.CpuView::cpuTicks).toList());
        assertEquals(RuntimeStepResult.CpuOutcome.NOT_EXECUTED, s.lastCpuResults().get(2).outcome());
    }
    @Test void staleLastCpuPageTablePreventsEveryParticipantFromExecuting() throws Exception {
        var run = simulator(3);
        for (int i = 0; i < 3; i++) submit(run, "p" + i, "INC", "INC");
        run.start();
        var resources = field(run, "processResources", ProcessResourceRegistry.class);
        field(run, "memory", MainMemory.class).release(resources.find(3).orElseThrow().residence().residentImage());
        assertThrows(ExecutionEngineException.class, run::step);
        var s = run.snapshot(); assertEquals(0, s.simulationTimeSeconds().orElseThrow());
        assertTrue(s.cpus().stream().allMatch(cpu -> cpu.cpuTicks() == 0 && cpu.cpu().accumulator() == 0));
        assertTrue(s.cpus().get(2).nextInstructionPhysicalAddress().isEmpty());
    }
    private SimulatorConfiguration configuration(int count) {
        return new SimulatorConfiguration(new MemoryConfiguration(count * 140 + 121, count * 140), 2048, 256,
                count, SchedulingPolicyId.FCFS, UserAllocationStrategy.FIRST_FIT, MemoryMode.PAGING, Optional.empty());
    }
    private SimulatorOrchestrator simulator(int count) {
        var run = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
        run.initialize(configuration(count)); return run;
    }
    @Test void historicalDefaultsAndExplicitPageConfigurationRemainCompatible() {
        assertEquals(MemoryMode.DYNAMIC, SimulatorConfiguration.defaults().memoryMode());
        assertTrue(SimulatorConfiguration.defaults().paging().isEmpty());
        assertEquals(Optional.of(new PagingConfiguration(4)), configuration(1).paging());
        assertThrows(IllegalArgumentException.class, () -> new SimulatorConfiguration(new MemoryConfiguration(256, 140),
                512, 64, 1, SchedulingPolicyId.FCFS, UserAllocationStrategy.FIRST_FIT, MemoryMode.PAGING,
                Optional.empty(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new SimulatorConfiguration(new MemoryConfiguration(256, 140),
                512, 64, 1, SchedulingPolicyId.FCFS, UserAllocationStrategy.FIRST_FIT, MemoryMode.DYNAMIC,
                Optional.empty(), Optional.of(new PagingConfiguration(4))));
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5})
    void globalPoolClockAffinityCompletionAndSnapshotsRemainCoherent(int count) {
        var run = simulator(count);
        for (int i = 0; i < count; i++) run.submitProgram(new ProgramImage("p" + i, Collections.nCopies(5, new IncInstruction())));
        run.submitProgram(new ProgramImage("future", List.of(new IncInstruction())), 7);
        run.start(); var initial = run.snapshot();
        assertEquals(30, initial.paging().frames().size()); assertEquals(1, initial.paging().unassignableCells());
        assertEquals(count, initial.paging().pageTables().size());
        assertEquals(2 * count, initial.paging().frames().stream().filter(SimulatorSnapshot.FrameView::occupied).count());
        assertThrows(UnsupportedOperationException.class, () -> initial.paging().pageTables().clear());
        assertTrue(initial.processes().stream().allMatch(p -> p.base().isEmpty() && p.limit() == 5));
        run.step(); var one = run.snapshot();
        assertEquals(1, one.simulationTimeSeconds().orElseThrow());
        assertTrue(one.cpus().stream().allMatch(cpu -> cpu.cpuTicks() == 1));
        for (int guard = 0; guard < 12 && run.snapshot().simulatorState() != SimulatorState.FINISHED; guard++) run.step();
        var done = run.snapshot(); assertEquals(SimulatorState.FINISHED, done.simulatorState());
        assertEquals(8, done.simulationTimeSeconds().orElseThrow());
        assertEquals(count + 1, done.completedProcesses().size());
        assertEquals(5 * count + 1, done.cpus().stream().mapToLong(SimulatorSnapshot.CpuView::cpuTicks).sum());
        assertTrue(done.paging().frames().stream().noneMatch(SimulatorSnapshot.FrameView::occupied));
        assertTrue(done.paging().pageTables().isEmpty());
        assertEquals(count, initial.paging().pageTables().size());
        run.reset(); assertTrue(run.snapshot().paging().frames().isEmpty());
    }
}
