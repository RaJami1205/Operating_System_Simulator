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
    private final MainMemory memory=new MainMemory(new MemoryConfiguration(236,140));
    private final SecondaryStorage storage=new SecondaryStorage(512,64);
    private final ProcessTable table=new ProcessTable();
    private final ProcessResourceRegistry registry=new ProcessResourceRegistry();
    private final ReadyQueue ready=new ReadyQueue();
    private final SuspendedReadyQueue suspended=new SuspendedReadyQueue();
    private final JobList jobs=new JobList();
    private final CpuRegisters<Instruction> cpu=new CpuRegisters<>();
    private final Dispatcher dispatcher=new Dispatcher(cpu,table,ready,registry,memory);
    private final KeyboardCompletionService input=new KeyboardCompletionService(new KeyboardDevice(),table,registry,ready,suspended,memory);
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
        var physicalBefore=java.util.stream.IntStream.range(0,84).mapToObj(memory::cell).toList();
        var finalContext=pcb.cpuContext();terminate(pid);completion.complete(pid);
        for(int address=0;address<84;address++) {
            if(address>=resource.kernel().base() && address<resource.kernel().endExclusive()) assertTrue(memory.isEmpty(address));
            else if(pid>1 && address==(pid-2)*28+PcbField.NEXT_PCB.offset()) {
                assertEquals(pid==3 ? PcbValue.Empty.INSTANCE : new PcbValue.Number(56), ((PcbContent)memory.read(address)).value());
            } else assertEquals(physicalBefore.get(address),memory.cell(address));
        }
        assertEquals(2,table.size());assertTrue(registry.find(pid).isEmpty());
        var remaining=table.entries();
        assertEquals(Optional.of(registry.find(remaining.get(1).processId()).orElseThrow().address()),remaining.getFirst().nextPcbAddress());
        assertTrue(remaining.getLast().nextPcbAddress().isEmpty());
        assertInstanceOf(EmptyContent.class,memory.read(resource.address().address()));
        assertThrows(RuntimeException.class,()->memory.release(resource.kernel()));
        assertThrows(RuntimeException.class,()->memory.release(((UserImageResidence.Resident)resource.residence()).allocation()));
        assertEquals(finalContext,completion.completed().getFirst().finalContext());
        var history=completion.completed();assertThrows(UnsupportedOperationException.class,history::clear);
        pcb.replaceCpuContext(CpuContext.<Instruction>initial().withProgramCounter(pcb.programStartAddress()));assertEquals(finalContext,history.getFirst().finalContext());
        for(var other:List.copyOf(table.entries())) { terminate(other.processId());completion.complete(other.processId()); }
        assertTrue(table.entries().isEmpty());assertTrue(registry.entries().isEmpty());
        assertEquals(140,memory.allocateUser(96).base());assertEquals(0,memory.allocateKernel(140).base());
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
    @Test void middleReuseHasNewIdentityAndChainFollowsAdmissionOrder() {
        add(1);add(2);add(3);
        var old=registry.find(2).orElseThrow().kernel();
        terminate(2);completion.complete(2);
        var fourth=add(4);var replacement=registry.find(4).orElseThrow().kernel();
        assertEquals(28,replacement.base());assertNotEquals(old.allocationId(),replacement.allocationId());
        assertEquals(List.of(1,3,4),table.entries().stream().map(ProcessControlBlock::processId).toList());
        assertEquals(Optional.of(new PcbAddress(28)),table.find(3).orElseThrow().nextPcbAddress());
        assertEquals(PcbMemoryImage.capture(fourth).cells(),java.util.stream.IntStream.range(28,56).mapToObj(memory::read).toList());
        var before=java.util.stream.IntStream.range(0,84).mapToObj(memory::cell).toList();
        assertThrows(RuntimeException.class,()->memory.release(old));
        assertThrows(RuntimeException.class,()->completion.complete(2));
        assertEquals(before,java.util.stream.IntStream.range(0,84).mapToObj(memory::cell).toList());
    }

    @ParameterizedTest @ValueSource(ints={0,13,27})
    void corruptFieldOrOwnerAnywhereRejectsBeforeDestructiveCleanup(int offset) throws Exception {
        add(1);var target=add(2);add(3);terminate(2);
        var field=MainMemory.class.getDeclaredField("positions");field.setAccessible(true);
        var cells=(MemoryContent[])field.get(memory);
        cells[28+offset]=cells[offset]; // Same field but a foreign canonical owner.
        var before=List.copyOf(Arrays.asList(cells.clone()));
        assertThrows(RuntimeException.class,()->completion.complete(2));
        assertEquals(before,Arrays.asList(cells));assertEquals(3,table.size());
        assertEquals(2,memory.readUserBlock(((UserImageResidence.Resident)registry.find(2).orElseThrow().residence()).allocation()).size());
        assertTrue(completion.completed().isEmpty());
        cells[28+offset]=PcbMemoryImage.capture(target).cells().get((offset+1)%28);
        var wrongField=List.copyOf(Arrays.asList(cells.clone()));
        assertThrows(RuntimeException.class,()->completion.complete(2));
        assertEquals(wrongField,Arrays.asList(cells));
    }

}
