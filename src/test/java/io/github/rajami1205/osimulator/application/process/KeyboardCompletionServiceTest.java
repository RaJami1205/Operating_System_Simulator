package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.io.KeyboardDevice;
import io.github.rajami1205.osimulator.model.job.*;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.scheduling.*;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KeyboardCompletionServiceTest {
    private final MainMemory memory=new MainMemory(new MemoryConfiguration(128,32));
    private final SecondaryStorage storage=new SecondaryStorage(512,64);
    private final ProcessTable table=new ProcessTable();
    private final ProcessResourceRegistry registry=new ProcessResourceRegistry();
    private final ReadyQueue ready=new ReadyQueue();
    private final SuspendedReadyQueue suspended=new SuspendedReadyQueue();
    private final KeyboardDevice device=new KeyboardDevice();
    private final KeyboardCompletionService service=new KeyboardCompletionService(device,table,registry,ready,suspended);
    private ProcessControlBlock blocked() {
        var jobs=new JobList();storage.storeProgram("p",List.of(new IncInstruction(),new IncInstruction()));
        jobs.add(new Job(1,"p",JobState.PENDING));
        new ProcessAdmissionService(jobs,storage,memory,table,new ProgramLoader(),ready,registry).admit(1);
        ready.remove(1);var pcb=table.find(1).orElseThrow();pcb.changeState(ProcessState.BLOCKED);return pcb;
    }
    @Test void registrationIsCanonicalUniqueAndHistoryIsImmutable() {
        var pcb=blocked();service.register(pcb);var history=service.pending();
        assertThrows(IllegalStateException.class,()->service.register(pcb));
        var other=new ProcessControlBlock(1,32,2);other.changeState(ProcessState.BLOCKED);
        assertThrows(IllegalStateException.class,()->service.register(other));
        assertEquals(List.of(new PendingKeyboardRequest(1,0)),history);
        assertThrows(UnsupportedOperationException.class,history::clear);service.remove(1);
        assertTrue(service.pending().isEmpty());assertEquals(1,history.size());
    }
    @Test void mismatchingSavedPcRejectsBeforeConsumingInputOrPublishingContext() {
        var pcb=blocked();service.register(pcb);pcb.setProgramCounter(1);device.submit(44);
        var before=pcb.cpuContext();assertThrows(IllegalStateException.class,service::drain);
        assertSame(before,pcb.cpuContext());assertEquals(1,service.pending().size());assertTrue(device.hasInput());
        pcb.setProgramCounter(0);service.drain();assertFalse(device.hasInput());assertEquals(44,pcb.cpuContext().dx());
        assertEquals(1,pcb.programCounter());assertEquals(List.of(1),ready.entries());
        assertTrue(service.drain().isEmpty());assertEquals(1,pcb.programCounter());
    }
    @Test void suspendedCompletionReplacesOnlyDxAndPcAndDoesNotEnqueueResidentReady() {
        var pcb=blocked();
        var before=new CpuContext<Instruction>(0,Optional.of(new IncInstruction()),1,2,3,4,
                new TextRegisterValue("old DX"),64,new TextRegisterValue("preserved AL"),new ConditionFlags(true,true));
        pcb.replaceCpuContext(before);service.register(pcb);
        new ProcessSwapService(memory,storage,table,registry,ready).swapOut(1);
        device.submit(255);assertTrue(service.drain().isEmpty());
        assertEquals(before.withDx(new NumericRegisterValue(255)).withProgramCounter(1),pcb.cpuContext());
        assertEquals("old DX",before.dxValue().textValue());
        assertThrows(NullPointerException.class,()->before.withDx(null));
        assertEquals(ProcessState.READY_SUSPENDED,pcb.state());assertTrue(ready.entries().isEmpty());
        assertEquals(List.of(1),suspended.entries());
    }
}
