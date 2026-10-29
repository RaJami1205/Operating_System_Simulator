package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.scheduling.ReadyQueue;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhysicalPcRuntimeTest {
    @Test void snapshotSeparatesLogicalContextFromNextPhysicalInstructionAndHistory() throws Exception {
        simulator.initialize(SimulatorConfiguration.defaults());
        assertTrue(simulator.snapshot().nextInstructionPhysicalAddress().isEmpty());
        submit("uniform", "ADD AX", "INC"); simulator.start();
        assertTrue(simulator.snapshot().nextInstructionPhysicalAddress().isEmpty());
        simulator.step();
        var first = simulator.snapshot();
        assertEquals(4097, first.cpu().orElseThrow().programCounter());
        assertEquals(Optional.of(141), first.nextInstructionPhysicalAddress());
        assertEquals(Optional.of(141), simulator.snapshot().nextInstructionPhysicalAddress());
        assertEquals(4097, simulator.snapshot().cpu().orElseThrow().programCounter());
        simulator.step();
        assertTrue(simulator.snapshot().nextInstructionPhysicalAddress().isEmpty());
        assertEquals(4098, simulator.snapshot().completedProcesses().getFirst().finalContext().programCounter());
        assertEquals(Optional.of(141), first.nextInstructionPhysicalAddress());
        assertEquals(4097, first.cpu().orElseThrow().programCounter());
    }

    @Test void staleAllocationSnapshotReportsControlledErrorInsteadOfNonresidence() throws Exception {
        simulator.initialize(SimulatorConfiguration.defaults());
        submit("uniform", "ADD AX", "INC"); simulator.start(); simulator.step();
        var memory = field("memory", MainMemory.class);
        var resource = field("processResources", ProcessResourceRegistry.class).find(1).orElseThrow();
        var stale = ((UserImageResidence.Resident) resource.residence()).allocation();
        memory.release(stale);
        var replacement = memory.allocateUser(1);
        assertEquals(stale.base(), replacement.base());
        var snapshot = simulator.snapshot();
        assertEquals(io.github.rajami1205.osimulator.application.lifecycle.SimulatorState.ERROR, snapshot.simulatorState());
        assertTrue(snapshot.nextInstructionPhysicalAddress().isEmpty());
        assertTrue(snapshot.addressTranslationError().isPresent());
        assertEquals(SimulatorSnapshot.Residency.RESIDENT, snapshot.processes().getFirst().residency());
        assertEquals(snapshot, simulator.snapshot());
    }

    @Test void terminalOwnerCannotProduceNextPhysicalInstruction() throws Exception {
        simulator.initialize(SimulatorConfiguration.defaults());
        submit("uniform", "ADD AX", "INC"); simulator.start(); simulator.step();
        var pcb = field("processTable", ProcessTable.class).find(1).orElseThrow();
        pcb.setProgramCounter(pcb.logicalCodeRange().endExclusive().value());
        assertTrue(simulator.snapshot().nextInstructionPhysicalAddress().isEmpty());
    }
    private final SimulatorOrchestrator simulator = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine());
    private void submit(String name, String... source) {
        simulator.submitProgram(new ProgramImage(name, new AsmParser().parse(List.of(source))));
    }
    private <T> T field(String name, Class<T> type) throws Exception {
        var field = SimulatorOrchestrator.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(simulator));
    }

    @Test void blockedRelocationPreservesLogicalRequestAndPreservesHistoricalSnapshot() throws Exception {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 512, 64));
        submit("input", "INT 09H", "INC");
        simulator.start(); simulator.step();
        var before = simulator.snapshot();
        assertEquals(List.of(new PendingKeyboardRequest(1, 4096)), before.pendingKeyboardRequests());
        simulator.swapOut(1);
        assertTrue(simulator.snapshot().nextInstructionPhysicalAddress().isEmpty());
        assertEquals(4096, simulator.snapshot().processes().getFirst().savedProgramCounter());
        var memory = field("memory", MainMemory.class);
        var occupied = memory.allocateUser(38);
        simulator.swapIn(1);
        assertEquals(List.of(new PendingKeyboardRequest(1, 4096)), simulator.pendingKeyboardRequests());
        assertEquals(4096, simulator.snapshot().processes().getFirst().savedProgramCounter());
        assertEquals(4096, before.pendingKeyboardRequests().getFirst().interruptLogicalPc());
        simulator.submitKeyboardInput(42);
        assertEquals(4097, simulator.snapshot().processes().getFirst().savedProgramCounter());
        simulator.step();
        assertEquals(4098, simulator.completedProcesses().getFirst().finalContext().programCounter());
        assertEquals(42, simulator.completedProcesses().getFirst().finalContext().dx());
        memory.release(occupied);
    }

    @Test void readyRelocationPreservesContextAndOffsetEight() throws Exception {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 512, 64));
        submit("p", Collections.nCopies(10, "INC").toArray(String[]::new));
        simulator.start();
        var pcb = field("processTable", ProcessTable.class).find(1).orElseThrow();
        var context = new CpuContext<Instruction>(4104, Optional.of(new IncInstruction()),
                1, 2, 3, 4, 5, 6, 7, new ConditionFlags(true, true));
        pcb.replaceCpuContext(context); pcb.stack().push(9);
        var swap = field("processSwapService", ProcessSwapService.class);
        var oldAllocation = ((UserImageResidence.Resident) field("processResources", ProcessResourceRegistry.class)
                .find(1).orElseThrow().residence()).allocation();
        swap.swapOut(1);
        var memory = field("memory", MainMemory.class);
        memory.allocateUser(38);
        swap.swapIn(1);
        assertThrows(io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException.class,
                () -> memory.translateInstructionAddress(oldAllocation, pcb.logicalCodeRange(), new LogicalAddress(pcb.programCounter())));
        assertEquals(178, pcb.programStartAddress());
        assertEquals(context, pcb.cpuContext());
        assertEquals(List.of(9), pcb.stack().values());
        var dispatcher = field("dispatcher", Dispatcher.class);
        dispatcher.dispatch(1);
        assertEquals(4104, simulator.snapshot().cpu().orElseThrow().programCounter());
        assertEquals(Optional.of(186), simulator.snapshot().nextInstructionPhysicalAddress());
        assertEquals(4104, simulator.snapshot().process().orElseThrow().savedProgramCounter());
        assertTrue(simulator.snapshot().program().stream().anyMatch(row -> row.address() == 186));
        simulator.step();
        assertEquals(4105, simulator.snapshot().cpu().orElseThrow().programCounter());
        assertEquals(Optional.of(187), simulator.snapshot().nextInstructionPhysicalAddress());
        assertEquals(2, simulator.snapshot().cpu().orElseThrow().accumulator());
    }

    @Test void completionDoesNotLeakFinalPcIntoNextOwner() throws Exception {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 512, 64));
        submit("first", "INT 20H", "INC", "INC"); submit("second", "ADD AX", "INC");
        simulator.start();
        while (simulator.completedProcesses().isEmpty()) simulator.step();
        assertEquals(4096, simulator.completedProcesses().getFirst().finalContext().programCounter());
        var next = field("processTable", ProcessTable.class).find(2).orElseThrow();
        assertEquals(4096, next.programCounter());
        assertTrue(simulator.snapshot().memory().get(140).content().isEmpty());
        simulator.step();
        assertEquals(4097, simulator.snapshot().cpu().orElseThrow().programCounter());
        assertEquals(Optional.of(144), simulator.snapshot().nextInstructionPhysicalAddress());
    }

    @Test void dispatchSavesAndRestoresLogicalAddressesAcrossDistinctBases() {
        var memory = new MainMemory(new MemoryConfiguration(160, 64));
        var cpu = new CpuRegisters<Instruction>(); var table = new ProcessTable();
        var ready = new ReadyQueue(); var resources = new ProcessResourceRegistry();
        for (int pid = 1; pid <= 2; pid++) {
            var user = memory.allocateUser(pid == 1 ? 28 : 10);
            var kernel = memory.allocateKernel(io.github.rajami1205.osimulator.model.memory.PcbField.PCB_SIZE);
            var pcb = new ProcessControlBlock(pid, user.base(), user.size());
            pcb.setProgramCounter(pid == 1 ? 4104 : 4100);
            pcb.changeState(ProcessState.READY); table.register(pcb); ready.enqueue(pid);
            memory.materializePcb(kernel, pcb);
            resources.register(pid, new ProcessResources(kernel, new PcbAddress(kernel.base()), new UserImageResidence.Resident(user)));
        }
        var dispatcher = new Dispatcher(cpu, table, ready, resources, memory);
        dispatcher.dispatch(1); assertEquals(4104, cpu.programCounter());
        cpu.writeAccumulator(17);
        var first = table.find(1).orElseThrow(); first.changeState(ProcessState.BLOCKED); dispatcher.release();
        assertEquals(4104, first.cpuContext().programCounter());
        dispatcher.dispatch(2); assertEquals(4100, cpu.programCounter());
        table.find(2).orElseThrow().changeState(ProcessState.BLOCKED); dispatcher.release();
        first.changeState(ProcessState.READY); ready.enqueue(1); dispatcher.dispatch(1);
        assertEquals(4104, cpu.programCounter()); assertEquals(17, cpu.accumulator());
    }
}
