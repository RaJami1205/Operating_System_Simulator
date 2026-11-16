package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.scheduling.ReadyQueue;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DispatcherTest {
    @Test void kernelWriteRejectsForeignBlockAfterSuccessfulPrevalidation() throws Exception {
        var first = add(1);
        add(2);
        dispatcher.dispatch(1);
        var previous = first.cpuContext();
        cpu.writeAccumulator(93);
        var active = cpu.snapshot();
        var before = java.util.stream.IntStream.range(0, memory.size()).mapToObj(memory::read).toList();
        var mappings = new HashMap<Integer, ProcessResources>(resources.entries()) {
            boolean inject = true;
            @Override public ProcessResources get(Object key) {
                if (inject && key.equals(1) && first.state() == ProcessState.READY) {
                    inject = false;
                    return super.get(2); // Only the sync lookup sees a foreign Kernel handle.
                }
                return super.get(key);
            }
        };
        var field = ProcessResourceRegistry.class.getDeclaredField("resources");
        field.setAccessible(true);
        field.set(resources, mappings);
        assertThrows(io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException.class,
                dispatcher::preempt);
        assertEquals(before, java.util.stream.IntStream.range(0, memory.size()).mapToObj(memory::read).toList());
        assertSame(first, dispatcher.owner().orElseThrow());
        assertEquals(ProcessState.RUNNING, first.state());
        assertSame(previous, first.cpuContext());
        assertEquals(active, cpu.snapshot());
        assertEquals(List.of(2), ready.entries());
        dispatcher.preempt();
        assertEquals(List.of(2, 1), ready.entries());
    }
    @Test void rejectedKernelCaptureRollsBackAndRetryCommitsOnce() throws Exception {
        var first = add(1);
        var second = add(2);
        add(3);
        dispatcher.dispatch(1, 7);
        var previous = first.cpuContext();
        var active = new CpuContext<Instruction>(4098, Optional.of(new IncInstruction()), 7, 8, 9, 10,
                new TextRegisterValue("DX"), 4, new NumericRegisterValue(2), new ConditionFlags(true, false));
        cpu.restore(active);
        var kernel = resources.find(1).orElseThrow().kernel();
        var before = java.util.stream.IntStream.range(kernel.base(), kernel.endExclusive()).mapToObj(memory::read).toList();
        var times = first.logicalTimes();
        var link = ProcessControlBlock.class.getDeclaredField("nextPcbAddress");
        link.setAccessible(true);
        link.set(first, null); // Capture fails after READY and provisional enqueue.
        assertThrows(NullPointerException.class, dispatcher::preempt);
        assertSame(first, dispatcher.owner().orElseThrow());
        assertEquals(ProcessState.RUNNING, first.state());
        assertSame(previous, first.cpuContext());
        assertEquals(active, cpu.snapshot());
        assertSame(times, first.logicalTimes());
        assertEquals(List.of(2, 3), ready.entries());
        assertEquals(before, java.util.stream.IntStream.range(kernel.base(), kernel.endExclusive()).mapToObj(memory::read).toList());
        first.setNextPcbAddress(Optional.empty());
        dispatcher.preempt();
        assertEquals(active, first.cpuContext());
        assertEquals(active, cpu.snapshot());
        assertEquals(ProcessState.READY, first.state());
        assertEquals(List.of(2, 3, 1), ready.entries());
        assertTrue(dispatcher.owner().isEmpty());
        assertEquals(new PcbValue.State(ProcessState.READY), ((PcbContent) memory.read(kernel.base() + PcbField.STATE.offset())).value());
        link.set(second, null);
        assertThrows(NullPointerException.class, () -> dispatcher.dispatch(2, 8));
        assertTrue(dispatcher.owner().isEmpty());
        assertEquals(List.of(2, 3, 1), ready.entries());
        assertEquals(ProcessState.READY, first.state());
        assertEquals(active, cpu.snapshot());
    }

    @Test void prevalidationRejectsDuplicateInvalidPcAndForeignAffinity() throws Exception {
        var first = add(1);
        add(2);
        dispatcher.dispatch(1);
        var previous = first.cpuContext();
        ready.enqueue(1);
        assertThrows(IllegalStateException.class, dispatcher::preempt);
        assertEquals(List.of(2, 1), ready.entries());
        ready.remove(1);
        cpu.setProgramCounter(4099);
        assertThrows(IllegalStateException.class, dispatcher::preempt);
        cpu.restore(previous);
        var other = new Dispatcher(new CpuId(1), cpu, table, ready, resources, memory);
        var owner = Dispatcher.class.getDeclaredField("owner");
        owner.setAccessible(true);
        owner.set(other, first);
        assertThrows(IllegalStateException.class, other::preempt);
        assertSame(first, dispatcher.owner().orElseThrow());
        assertSame(previous, first.cpuContext());
        assertEquals(ProcessState.RUNNING, first.state());
        assertEquals(List.of(2), ready.entries());
    }
    @Test void logicalDispatchIsPublishedOnlyOnSuccessAndSurvivesRedispatch() {
        var pcb = add(1);
        assertThrows(IllegalArgumentException.class, () -> dispatcher.dispatch(1, -1));
        assertTrue(pcb.logicalTimes().firstDispatchTime().isEmpty());
        assertEquals(ProcessState.READY, pcb.state()); assertTrue(dispatcher.owner().isEmpty());
        dispatcher.dispatch(1, 3);
        assertEquals(OptionalLong.of(3), pcb.logicalTimes().firstDispatchTime());
        pcb.changeState(ProcessState.BLOCKED); dispatcher.release();
        pcb.changeState(ProcessState.READY); ready.enqueue(1); dispatcher.dispatch(1, 7);
        assertEquals(OptionalLong.of(3), pcb.logicalTimes().firstDispatchTime());
    }
    private final CpuRegisters<Instruction> cpu = new CpuRegisters<>();
    private final MainMemory memory = new MainMemory(new MemoryConfiguration(224,96));
    private final ProcessTable table = new ProcessTable();
    private final ReadyQueue ready = new ReadyQueue();
    private final ProcessResourceRegistry resources = new ProcessResourceRegistry();
    private final Dispatcher dispatcher = new Dispatcher(cpu,table,ready,resources,memory);

    private ProcessControlBlock add(int pid) {
        var user=memory.allocateUser(3);var kernel=memory.allocateKernel(io.github.rajami1205.osimulator.model.memory.PcbField.PCB_SIZE);
        var pcb=new ProcessControlBlock(pid,user.base(),3);
        memory.materializePcb(kernel,pcb);
        pcb.changeState(ProcessState.READY);table.register(pcb);ready.enqueue(pid);
        resources.register(pid,new ProcessResources(kernel,new PcbAddress(kernel.base()),new UserImageResidence.Resident(user)));
        return pcb;
    }
    @Test void restoresAndSavesEveryFieldAndDoesNotLeakIntoNextProcess() {
        var p1=add(1);var p2=add(2);
        var context=new CpuContext<Instruction>(4097,Optional.of(new IncInstruction()),7,8,9,10,
                new TextRegisterValue("DX text"),0x40,new TextRegisterValue("AL text"),new ConditionFlags(true,true));
        p1.replaceCpuContext(context);p1.stack().push(6);p1.openFiles().open("notes");
        dispatcher.dispatch(1);
        assertSame(p1,dispatcher.owner().orElseThrow());assertEquals(context,cpu.snapshot());
        assertEquals(List.of(2),ready.entries());assertEquals(ProcessState.RUNNING,p1.state());
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(2));
        assertThrows(IllegalStateException.class,dispatcher::release);
        var finalContext=context.withProgramCounter(4098).withDx(new NumericRegisterValue(255));
        cpu.restore(finalContext);p1.changeState(ProcessState.BLOCKED);dispatcher.release();
        assertEquals(finalContext,p1.cpuContext());assertEquals(finalContext,cpu.snapshot());
        assertTrue(dispatcher.owner().isEmpty());assertEquals(List.of(6),p1.stack().values());
        assertTrue(p1.openFiles().contains("notes"));
        dispatcher.dispatch(2);
        assertEquals(CpuContext.initial().withProgramCounter(p2.logicalCodeRange().base().value()),cpu.snapshot());assertSame(p2,dispatcher.owner().orElseThrow());
        p2.changeState(ProcessState.TERMINATED);dispatcher.release();
        assertEquals(CpuContext.initial().withProgramCounter(p2.logicalCodeRange().base().value()),p2.cpuContext());
    }
    @Test void rejectedCandidatesPreserveHeadCpuAndOwnership() {
        var p1=add(1);add(2);var before=cpu.snapshot();
        ready.remove(2);
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(2));
        ready.enqueue(2);
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(99));
        p1.setProgramCounter(4099);assertThrows(IllegalStateException.class,()->dispatcher.dispatch(1));
        p1.setProgramCounter(4096);p1.changeState(ProcessState.READY_SUSPENDED);
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(1));
        p1.changeState(ProcessState.READY);resources.remove(1);
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(1));
        assertEquals(List.of(1,2),ready.entries());assertEquals(before,cpu.snapshot());
        assertTrue(dispatcher.owner().isEmpty());
    }

    @Test void dispatchesMiddleReadyCandidateWithoutReorderingOthers() {
        var first = add(1); var middle = add(2); var last = add(3);
        middle.replaceCpuContext(middle.cpuContext().withDx(new TextRegisterValue("selected")));
        dispatcher.dispatch(2, 7);
        assertEquals(List.of(1, 3), ready.entries());
        assertSame(middle, dispatcher.owner().orElseThrow());
        assertEquals(middle.cpuContext(), cpu.snapshot());
        assertEquals(ProcessState.READY, first.state()); assertEquals(ProcessState.READY, last.state());
        assertEquals(OptionalLong.of(7), middle.logicalTimes().firstDispatchTime());
        var kernel = resources.find(2).orElseThrow().kernel();
        assertEquals(new PcbValue.State(ProcessState.RUNNING),
                ((PcbContent) memory.read(kernel.base() + PcbField.STATE.offset())).value());
        assertThrows(IllegalStateException.class, () -> dispatcher.dispatch(1, 7));
        assertEquals(List.of(1, 3), ready.entries());
    }

    @Test void lateImageFailureRestoresCpuAndStateWithoutLosingQueuePosition() throws Exception {
        add(1); var middle = add(2); add(3);
        cpu.writeAccumulator(47);
        var before = cpu.snapshot(); var context = middle.cpuContext(); var times = middle.logicalTimes();
        var cells = java.util.stream.IntStream.range(0, memory.size()).mapToObj(memory::read).toList();
        // La captura de imagen ocurre después de restaurar CPU y marcar RUNNING.
        var link = ProcessControlBlock.class.getDeclaredField("nextPcbAddress"); link.setAccessible(true);
        link.set(middle, null);
        assertThrows(NullPointerException.class, () -> dispatcher.dispatch(2, 4));
        assertEquals(List.of(1, 2, 3), ready.entries()); assertTrue(dispatcher.owner().isEmpty());
        assertEquals(before, cpu.snapshot()); assertSame(context, middle.cpuContext());
        assertSame(times, middle.logicalTimes()); assertEquals(ProcessState.READY, middle.state());
        assertEquals(cells, java.util.stream.IntStream.range(0, memory.size()).mapToObj(memory::read).toList());
        middle.setNextPcbAddress(Optional.empty());
        dispatcher.dispatch(2, 4);
        assertEquals(List.of(1, 3), ready.entries()); assertSame(middle, dispatcher.owner().orElseThrow());
    }

    @Test void staleResidentHandleRejectsNonHeadWithoutMutation() {
        add(1); var second = add(2);
        var resident = (UserImageResidence.Resident) resources.find(2).orElseThrow().residence();
        memory.release(resident.allocation());
        var before = cpu.snapshot();
        assertThrows(io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException.class,
                () -> dispatcher.dispatch(2));
        assertEquals(List.of(1, 2), ready.entries()); assertEquals(before, cpu.snapshot());
        assertTrue(dispatcher.owner().isEmpty()); assertEquals(ProcessState.READY, second.state());
        assertTrue(second.logicalTimes().firstDispatchTime().isEmpty());
    }
}
