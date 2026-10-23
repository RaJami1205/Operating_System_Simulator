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
        simulator.initialize(SimulatorConfiguration.defaults());
        submit("input", "INT 09H", "INC");
        simulator.start(); simulator.step(); simulator.step();
        var before = simulator.snapshot();
        assertEquals(List.of(new PendingKeyboardRequest(1, 32)), before.pendingKeyboardRequests());
        simulator.swapOut(1);
        var memory = field("memory", MainMemory.class);
        var occupied = memory.allocateUser(38);
        simulator.swapIn(1);
        assertEquals(List.of(new PendingKeyboardRequest(1, 70)), simulator.pendingKeyboardRequests());
        assertEquals(70, simulator.snapshot().processes().getFirst().savedProgramCounter());
        assertEquals(32, before.pendingKeyboardRequests().getFirst().interruptPc());
        simulator.submitKeyboardInput(42);
        assertEquals(71, simulator.snapshot().processes().getFirst().savedProgramCounter());
        simulator.step();
        assertEquals(72, simulator.completedProcesses().getFirst().finalContext().programCounter());
        assertEquals(42, simulator.completedProcesses().getFirst().finalContext().dx());
        memory.release(occupied);
    }

    @Test void readyRelocationPreservesContextAndOffsetEight() throws Exception {
        simulator.initialize(SimulatorConfiguration.defaults());
        submit("p", Collections.nCopies(10, "INC").toArray(String[]::new));
        simulator.start();
        var pcb = field("processTable", ProcessTable.class).find(1).orElseThrow();
        var context = new CpuContext<Instruction>(40, Optional.of(new IncInstruction()),
                1, 2, 3, 4, 5, 6, 7, new ConditionFlags(true, true));
        pcb.replaceCpuContext(context); pcb.stack().push(9);
        var swap = field("processSwapService", ProcessSwapService.class);
        swap.swapOut(1);
        var memory = field("memory", MainMemory.class);
        memory.allocateUser(38);
        swap.swapIn(1);
        assertEquals(70, pcb.programStartAddress());
        assertEquals(context.withProgramCounter(78), pcb.cpuContext());
        assertEquals(List.of(9), pcb.stack().values());
        var dispatcher = field("dispatcher", Dispatcher.class);
        dispatcher.dispatch(1);
        assertEquals(78, simulator.snapshot().cpu().orElseThrow().programCounter());
        assertEquals(78, simulator.snapshot().process().orElseThrow().savedProgramCounter());
        assertTrue(simulator.snapshot().program().stream().anyMatch(row -> row.address() == 78));
    }

    @Test void completionDoesNotLeakFinalPcIntoNextOwner() throws Exception {
        simulator.initialize(SimulatorConfiguration.defaults());
        submit("first", "INT 20H", "INC", "INC"); submit("second", "ADD AX");
        simulator.start();
        while (simulator.completedProcesses().isEmpty()) simulator.step();
        assertEquals(32, simulator.completedProcesses().getFirst().finalContext().programCounter());
        var next = field("processTable", ProcessTable.class).find(2).orElseThrow();
        assertEquals(35, next.programCounter());
        assertTrue(simulator.snapshot().memory().get(32).content().isEmpty());
        simulator.step();
        assertEquals(35, simulator.snapshot().cpu().orElseThrow().programCounter());
        assertNotEquals(32, simulator.snapshot().cpu().orElseThrow().programCounter());
    }

    @Test void dispatchSavesAndRestoresPhysicalAddressesAcrossDistinctBases() {
        var memory = new MainMemory(new MemoryConfiguration(128, 32));
        var cpu = new CpuRegisters<Instruction>(); var table = new ProcessTable();
        var ready = new ReadyQueue(); var resources = new ProcessResourceRegistry();
        for (int pid = 1; pid <= 2; pid++) {
            var user = memory.allocateUser(pid == 1 ? 28 : 10);
            var kernel = memory.allocateKernel(1);
            var pcb = new ProcessControlBlock(pid, user.base(), user.size());
            pcb.setProgramCounter(pid == 1 ? 40 : 64);
            pcb.changeState(ProcessState.READY); table.register(pcb); ready.enqueue(pid);
            memory.writePcb(kernel, 0, pcb);
            resources.register(pid, new ProcessResources(kernel, new PcbAddress(kernel.base()), new UserImageResidence.Resident(user)));
        }
        var dispatcher = new Dispatcher(cpu, table, ready, resources, memory);
        dispatcher.dispatch(1); assertEquals(40, cpu.programCounter());
        cpu.writeAccumulator(17);
        var first = table.find(1).orElseThrow(); first.changeState(ProcessState.BLOCKED); dispatcher.release();
        assertEquals(40, first.cpuContext().programCounter());
        dispatcher.dispatch(2); assertEquals(64, cpu.programCounter());
        table.find(2).orElseThrow().changeState(ProcessState.BLOCKED); dispatcher.release();
        first.changeState(ProcessState.READY); ready.enqueue(1); dispatcher.dispatch(1);
        assertEquals(40, cpu.programCounter()); assertEquals(17, cpu.accumulator());
    }
}
