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
    private final MainMemory memory = new MainMemory(new MemoryConfiguration(160,64));
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
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(2));
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(99));
        p1.setProgramCounter(4099);assertThrows(IllegalStateException.class,()->dispatcher.dispatch(1));
        p1.setProgramCounter(4096);p1.changeState(ProcessState.READY_SUSPENDED);
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(1));
        p1.changeState(ProcessState.READY);resources.remove(1);
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(1));
        assertEquals(List.of(1,2),ready.entries());assertEquals(before,cpu.snapshot());
        assertTrue(dispatcher.owner().isEmpty());
    }
}
