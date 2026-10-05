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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class ProcessCompletionServiceTest {
    private final MainMemory memory=new MainMemory(new MemoryConfiguration(128,32));
    private final SecondaryStorage storage=new SecondaryStorage(512,64);
    private final ProcessTable table=new ProcessTable();
    private final ProcessResourceRegistry registry=new ProcessResourceRegistry();
    private final ReadyQueue ready=new ReadyQueue();
    private final SuspendedReadyQueue suspended=new SuspendedReadyQueue();
    private final JobList jobs=new JobList();
    private final CpuRegisters<Instruction> cpu=new CpuRegisters<>();
    private final Dispatcher dispatcher=new Dispatcher(cpu,table,ready,registry,memory);
    private final KeyboardCompletionService input=new KeyboardCompletionService(new KeyboardDevice(),table,registry,ready,suspended);
    private final ProcessCompletionService completion=new ProcessCompletionService(memory,storage,table,registry,ready,suspended,input,dispatcher,java.time.Clock.fixed(java.time.Instant.parse("2026-10-01T12:00:00Z"), java.time.ZoneOffset.UTC));
    private final ProcessAdmissionService admission=new ProcessAdmissionService(jobs,storage,memory,table,new ProgramLoader(),ready,registry);
    private ProcessControlBlock add(int pid) {
        storage.storeProgram("p"+pid,List.of(new IncInstruction(),new IncInstruction()));
        jobs.add(new Job(pid,"p"+pid,JobState.PENDING));admission.admit(pid);
        return table.find(pid).orElseThrow();
    }
    private void terminate(int pid) {
        ready.remove(pid);table.find(pid).orElseThrow().changeState(ProcessState.TERMINATED);
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void unlinksHeadMiddleAndTailAndReleasesOriginalHandles(int pid) {
        add(1);add(2);add(3);var pcb=table.find(pid).orElseThrow();
        var resource=registry.find(pid).orElseThrow();
        pcb.replaceCpuContext(pcb.cpuContext().withDx(new TextRegisterValue("final")));
        var finalContext=pcb.cpuContext();terminate(pid);completion.complete(pid);
        assertEquals(2,table.size());assertTrue(registry.find(pid).isEmpty());
        var remaining=table.entries();
        assertEquals(Optional.of(registry.find(remaining.get(1).processId()).orElseThrow().address()),remaining.getFirst().nextPcbAddress());
        assertTrue(remaining.getLast().nextPcbAddress().isEmpty());
        assertInstanceOf(EmptyContent.class,memory.read(resource.address().address()));
        assertThrows(RuntimeException.class,()->memory.release(resource.kernel()));
        assertThrows(RuntimeException.class,()->memory.release(((UserImageResidence.Resident)resource.residence()).allocation()));
        assertEquals(finalContext,completion.completed().getFirst().finalContext());
        var history=completion.completed();assertThrows(UnsupportedOperationException.class,history::clear);
        pcb.replaceCpuContext(CpuContext.initial());assertEquals(finalContext,history.getFirst().finalContext());
        for(var other:List.copyOf(table.entries())) { terminate(other.processId());completion.complete(other.processId()); }
        assertTrue(table.entries().isEmpty());assertTrue(registry.entries().isEmpty());
        assertEquals(32,memory.allocateUser(96).base());assertEquals(0,memory.allocateKernel(32).base());
        assertEquals(1,history.size());assertEquals(3,completion.completed().size());
        assertThrows(IllegalStateException.class,()->completion.complete(pid));
    }
    @Test void badIncomingOrOutgoingLinkFailsBeforeAnyRelease() {
        var first=add(1);var second=add(2);add(3);var resource=registry.find(2).orElseThrow();terminate(2);
        first.setNextPcbAddress(Optional.empty());
        assertThrows(IllegalStateException.class,()->completion.complete(2));
        first.setNextPcbAddress(Optional.of(resource.address()));second.setNextPcbAddress(Optional.empty());
        assertThrows(IllegalStateException.class,()->completion.complete(2));
        assertEquals(3,table.size());assertEquals(resource,registry.find(2).orElseThrow());
        memory.validatePcbAllocation(resource.kernel(),second);
        assertEquals(2,memory.readUserBlock(((UserImageResidence.Resident)resource.residence()).allocation()).size());
        assertTrue(completion.completed().isEmpty());
    }
    @Test void rejectsReadyMembershipAndOwnerBeforeCleanup() {
        var pcb=add(1);pcb.changeState(ProcessState.TERMINATED);
        assertThrows(IllegalStateException.class,()->completion.complete(1));
        pcb.changeState(ProcessState.READY);dispatcher.dispatch(1);pcb.changeState(ProcessState.TERMINATED);
        assertThrows(IllegalStateException.class,()->completion.complete(1));
        dispatcher.release();completion.complete(1);assertEquals(0,table.size());
    }
    @Test void suspendedCleanupReleasesSwapWithoutReadingStaleUserBounds() {
        var pcb=add(1);ready.remove(1);pcb.changeState(ProcessState.BLOCKED);input.register(pcb);
        new ProcessSwapService(memory,storage,table,registry,ready).swapOut(1);
        var resource=registry.find(1).orElseThrow();var disk=((UserImageResidence.Suspended)resource.residence()).allocation();
        pcb.changeState(ProcessState.TERMINATED);completion.complete(1);
        assertTrue(input.pending().isEmpty());assertTrue(table.entries().isEmpty());
        assertThrows(RuntimeException.class,()->storage.releaseSwap(disk));
        assertEquals(64,storage.allocateSwap(64).size());
        assertInstanceOf(EmptyContent.class,memory.read(resource.address().address()));
        assertEquals(2,storage.readProgram("p1").size());
    }
}
