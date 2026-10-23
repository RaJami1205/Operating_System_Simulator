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
    private final SimulatorOrchestrator simulator = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine());
    private void submit(String name, String... source) {
        simulator.submitProgram(new ProgramImage(name, new AsmParser().parse(List.of(source))));
    }
    private <T> T field(String name, Class<T> type) throws Exception {
        var field = SimulatorOrchestrator.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(simulator));
    }

    @Test void blockedRelocationRebasesPublicRequestAndPreservesHistoricalSnapshot() throws Exception {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 64), 512, 64));
        submit("input", "INT 09H", "INC");
        simulator.start(); simulator.step(); simulator.step();
        var before = simulator.snapshot();
        assertEquals(List.of(new PendingKeyboardRequest(1, 64)), before.pendingKeyboardRequests());
        simulator.swapOut(1);
        var memory = field("memory", MainMemory.class);
        var occupied = memory.allocateUser(38);
        simulator.swapIn(1);
        assertEquals(List.of(new PendingKeyboardRequest(1, 102)), simulator.pendingKeyboardRequests());
        assertEquals(102, simulator.snapshot().processes().getFirst().savedProgramCounter());
        assertEquals(64, before.pendingKeyboardRequests().getFirst().interruptPc());
        simulator.submitKeyboardInput(42);
        assertEquals(103, simulator.snapshot().processes().getFirst().savedProgramCounter());
        simulator.step();
        assertEquals(104, simulator.completedProcesses().getFirst().finalContext().programCounter());
        assertEquals(42, simulator.completedProcesses().getFirst().finalContext().dx());
        memory.release(occupied);
    }

    @Test void readyRelocationPreservesContextAndOffsetEight() throws Exception {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 64), 512, 64));
        submit("p", Collections.nCopies(10, "INC").toArray(String[]::new));
        simulator.start();
        var pcb = field("processTable", ProcessTable.class).find(1).orElseThrow();
        var context = new CpuContext<Instruction>(72, Optional.of(new IncInstruction()),
                1, 2, 3, 4, 5, 6, 7, new ConditionFlags(true, true));
        pcb.replaceCpuContext(context); pcb.stack().push(9);
        var swap = field("processSwapService", ProcessSwapService.class);
        swap.swapOut(1);
        var memory = field("memory", MainMemory.class);
        memory.allocateUser(38);
        swap.swapIn(1);
        assertEquals(102, pcb.programStartAddress());
        assertEquals(context.withProgramCounter(110), pcb.cpuContext());
        assertEquals(List.of(9), pcb.stack().values());
        var dispatcher = field("dispatcher", Dispatcher.class);
        dispatcher.dispatch(1);
        assertEquals(110, simulator.snapshot().cpu().orElseThrow().programCounter());
        assertEquals(110, simulator.snapshot().process().orElseThrow().savedProgramCounter());
        assertTrue(simulator.snapshot().program().stream().anyMatch(row -> row.address() == 110));
    }

    @Test void completionDoesNotLeakFinalPcIntoNextOwner() throws Exception {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 64), 512, 64));
        submit("first", "INT 20H", "INC", "INC"); submit("second", "ADD AX");
        simulator.start();
        while (simulator.completedProcesses().isEmpty()) simulator.step();
        assertEquals(64, simulator.completedProcesses().getFirst().finalContext().programCounter());
        var next = field("processTable", ProcessTable.class).find(2).orElseThrow();
        assertEquals(67, next.programCounter());
        assertTrue(simulator.snapshot().memory().get(64).content().isEmpty());
        simulator.step();
        assertEquals(67, simulator.snapshot().cpu().orElseThrow().programCounter());
        assertNotEquals(64, simulator.snapshot().cpu().orElseThrow().programCounter());
    }

    @Test void dispatchSavesAndRestoresPhysicalAddressesAcrossDistinctBases() {
        var memory = new MainMemory(new MemoryConfiguration(160, 64));
        var cpu = new CpuRegisters<Instruction>(); var table = new ProcessTable();
        var ready = new ReadyQueue(); var resources = new ProcessResourceRegistry();
        for (int pid = 1; pid <= 2; pid++) {
            var user = memory.allocateUser(pid == 1 ? 28 : 10);
            var kernel = memory.allocateKernel(io.github.rajami1205.osimulator.model.memory.PcbField.PCB_SIZE);
            var pcb = new ProcessControlBlock(pid, user.base(), user.size());
            pcb.setProgramCounter(pid == 1 ? 72 : 96);
            pcb.changeState(ProcessState.READY); table.register(pcb); ready.enqueue(pid);
            memory.materializePcb(kernel, pcb);
            resources.register(pid, new ProcessResources(kernel, new PcbAddress(kernel.base()), new UserImageResidence.Resident(user)));
        }
        var dispatcher = new Dispatcher(cpu, table, ready, resources, memory);
        dispatcher.dispatch(1); assertEquals(72, cpu.programCounter());
        cpu.writeAccumulator(17);
        var first = table.find(1).orElseThrow(); first.changeState(ProcessState.BLOCKED); dispatcher.release();
        assertEquals(72, first.cpuContext().programCounter());
        dispatcher.dispatch(2); assertEquals(96, cpu.programCounter());
        table.find(2).orElseThrow().changeState(ProcessState.BLOCKED); dispatcher.release();
        first.changeState(ProcessState.READY); ready.enqueue(1); dispatcher.dispatch(1);
        assertEquals(72, cpu.programCounter()); assertEquals(17, cpu.accumulator());
    }
}
