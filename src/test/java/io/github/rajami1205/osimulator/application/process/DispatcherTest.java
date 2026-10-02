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
    private final CpuRegisters<Instruction> cpu = new CpuRegisters<>();
    private final MainMemory memory = new MainMemory(new MemoryConfiguration(128,32));
    private final ProcessTable table = new ProcessTable();
    private final ReadyQueue ready = new ReadyQueue();
    private final ProcessResourceRegistry resources = new ProcessResourceRegistry();
    private final Dispatcher dispatcher = new Dispatcher(cpu,table,ready,resources,memory);

    private ProcessControlBlock add(int pid) {
        var user=memory.allocateUser(3);var kernel=memory.allocateKernel(1);
        var pcb=new ProcessControlBlock(pid,user.base(),3);
        memory.writePcb(kernel,0,pcb);
        pcb.changeState(ProcessState.READY);table.register(pcb);ready.enqueue(pid);
        resources.register(pid,new ProcessResources(kernel,new PcbAddress(kernel.base()),new UserImageResidence.Resident(user)));
        return pcb;
    }
    @Test void restoresAndSavesEveryFieldAndDoesNotLeakIntoNextProcess() {
        var p1=add(1);var p2=add(2);
        var context=new CpuContext<Instruction>(1,Optional.of(new IncInstruction()),7,8,9,10,
                new TextRegisterValue("DX text"),0x40,new TextRegisterValue("AL text"),new ConditionFlags(true,true));
        p1.replaceCpuContext(context);p1.stack().push(6);p1.openFiles().open("notes");
        dispatcher.dispatch(1);
        assertSame(p1,dispatcher.owner().orElseThrow());assertEquals(context,cpu.snapshot());
        assertEquals(List.of(2),ready.entries());assertEquals(ProcessState.RUNNING,p1.state());
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(2));
        assertThrows(IllegalStateException.class,dispatcher::release);
        var finalContext=context.withProgramCounter(2).withDx(new NumericRegisterValue(255));
        cpu.restore(finalContext);p1.changeState(ProcessState.BLOCKED);dispatcher.release();
        assertEquals(finalContext,p1.cpuContext());assertEquals(finalContext,cpu.snapshot());
        assertTrue(dispatcher.owner().isEmpty());assertEquals(List.of(6),p1.stack().values());
        assertTrue(p1.openFiles().contains("notes"));
        dispatcher.dispatch(2);
        assertEquals(CpuContext.initial(),cpu.snapshot());assertSame(p2,dispatcher.owner().orElseThrow());
        p2.changeState(ProcessState.TERMINATED);dispatcher.release();
        assertEquals(CpuContext.initial(),p2.cpuContext());
    }
    @Test void rejectedCandidatesPreserveHeadCpuAndOwnership() {
        var p1=add(1);add(2);var before=cpu.snapshot();
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(2));
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(99));
        p1.setProgramCounter(3);assertThrows(IllegalStateException.class,()->dispatcher.dispatch(1));
        p1.setProgramCounter(0);p1.changeState(ProcessState.READY_SUSPENDED);
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(1));
        p1.changeState(ProcessState.READY);resources.remove(1);
        assertThrows(IllegalStateException.class,()->dispatcher.dispatch(1));
        assertEquals(List.of(1,2),ready.entries());assertEquals(before,cpu.snapshot());
        assertTrue(dispatcher.owner().isEmpty());
    }
}
