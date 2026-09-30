package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.storage.*;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.job.*;
import io.github.rajami1205.osimulator.model.scheduling.*;
import io.github.rajami1205.osimulator.support.MemoryFaults;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ProcessSwapServiceTest {
    private final MainMemory memory=new MainMemory(new MemoryConfiguration(128,32));
    private final SecondaryStorage storage=new SecondaryStorage(512,64);
    private final ProcessTable table=new ProcessTable();
    private final ProcessResourceRegistry registry=new ProcessResourceRegistry();
    private final ReadyQueue queue=new ReadyQueue();
    private final JobList jobs=new JobList();
    private final ProcessAdmissionService admission=new ProcessAdmissionService(jobs,storage,memory,table,new ProgramLoader(),queue,registry);
    private final ProcessSwapService swap=new ProcessSwapService(memory,storage,table,registry,queue);
    private ProcessControlBlock admit(int pid,int size) {
        String name="p"+pid;
        storage.storeProgram(name,Collections.nCopies(size,new IncInstruction()));
        jobs.add(new Job(pid,name,JobState.PENDING));
        assertEquals(new AdmissionResult.Admitted(pid),admission.admit(pid));
        return table.find(pid).orElseThrow();
    }
    private ProcessResources resource(int pid) { return registry.find(pid).orElseThrow(); }
    private MemoryAllocation user(int pid) { return ((UserImageResidence.Resident)resource(pid).residence()).allocation(); }
    private StorageAllocation disk(int pid) { return ((UserImageResidence.Suspended)resource(pid).residence()).allocation(); }

    @Test void roundTripRelocatesCurrentImagePreservingCanonicalPcbAndAllRuntimeState() {
        var pcb=admit(1,3);admit(2,2);admit(3,2);
        var resources=resource(1);var original=user(1);
        memory.writeInstruction(original,0,new DecInstruction());
        var image=memory.readUserBlock(original);
        pcb.setProgramCounter(2);pcb.stack().push(7);pcb.openFiles().open("file");pcb.setPriority(9);
        var context=pcb.cpuContext();var accounting=pcb.accounting();var link=pcb.nextPcbAddress();
        storage.createUserFile("file");storage.writeUserFile("file","hello");var index=storage.entries();
        assertEquals(new SwapResult.Completed(),swap.swapOut(1));
        assertEquals(ProcessState.READY_SUSPENDED,pcb.state());
        assertThrows(IllegalStateException.class,pcb::memoryBounds);
        assertThrows(IllegalStateException.class,pcb::programStartAddress);
        assertThrows(IllegalStateException.class,pcb::programEndAddressExclusive);
        assertEquals(3,pcb.instructionCount());assertEquals(List.of(2,3),queue.entries());
        assertEquals(image,storage.readSwapBlock(disk(1)));
        assertSame(pcb,((PcbContent)memory.read(resources.address().address())).pcb());
        assertEquals(resources.kernel(),resource(1).kernel());assertEquals(link,pcb.nextPcbAddress());
        assertSame(pcb,table.find(1).orElseThrow());assertEquals(3,table.size());
        assertThrows(RuntimeException.class,()->memory.readUserBlock(original));
        var occupied=memory.allocateUser(3);assertEquals(original.base(),occupied.base());
        var oldSwap=disk(1);
        swap.swapIn(1);
        assertEquals(ProcessState.READY,pcb.state());assertNotEquals(original.base(),pcb.memoryBounds().base());
        assertEquals(new ProcessMemoryBounds(user(1).base(),3),pcb.memoryBounds());
        assertEquals(image,memory.readUserBlock(user(1)));assertEquals(List.of(2,3,1),queue.entries());
        assertThrows(RuntimeException.class,()->storage.readSwapBlock(oldSwap));
        assertSame(context,pcb.cpuContext());assertEquals(2,pcb.programCounter());
        assertSame(accounting,pcb.accounting());assertEquals(List.of(7),pcb.stack().values());
        assertTrue(pcb.openFiles().contains("file"));assertEquals(9,pcb.priority());
        assertEquals(index,storage.entries());assertEquals("hello",storage.readUserFile("file"));
        assertEquals(Collections.nCopies(3,new IncInstruction()),storage.readProgram("p1"));
        assertEquals(Optional.of(2),new FcfsProcessScheduler(queue,table).selectNext());
    }

    @Test void eligibleBlockedNeverEntersReadyQueue() {
        var pcb=admit(1,2);queue.remove(1);pcb.changeState(ProcessState.BLOCKED);
        swap.swapOut(1);assertEquals(ProcessState.BLOCKED_SUSPENDED,pcb.state());assertTrue(queue.entries().isEmpty());
        swap.swapIn(1);assertEquals(ProcessState.BLOCKED,pcb.state());assertTrue(queue.entries().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value=ProcessState.class,names={"NEW","RUNNING","TERMINATED","READY_SUSPENDED","BLOCKED_SUSPENDED"})
    void rejectsInvalidResidentStateWithoutMutation(ProcessState state) {
        var pcb=admit(1,2);var original=resource(1);pcb.changeState(state);
        assertThrows(IllegalStateException.class,()->swap.swapOut(1));
        assertThrows(IllegalStateException.class,()->swap.swapIn(1));
        assertSame(original,resource(1));assertEquals(List.of(1),queue.entries());
    }

    @Test void rejectsDuplicateOperationsAndInconsistentQueueKernelAndIdentity() {
        var pcb=admit(1,2);
        assertThrows(IllegalStateException.class,()->swap.swapIn(1));
        queue.remove(1);assertThrows(IllegalStateException.class,()->swap.swapOut(1));queue.enqueue(1);
        swap.swapOut(1);assertThrows(IllegalStateException.class,()->swap.swapOut(1));
        queue.enqueue(1);assertThrows(IllegalStateException.class,()->swap.swapIn(1));queue.remove(1);swap.swapIn(1);
        memory.writePcb(resource(1).kernel(),0,new ProcessControlBlock(1,32,2));
        assertThrows(RuntimeException.class,()->swap.swapOut(1));
        memory.writePcb(resource(1).kernel(),0,pcb);memory.release(user(1));
        assertThrows(RuntimeException.class,()->swap.swapOut(1));
        assertThrows(IllegalArgumentException.class,()->swap.swapOut(999));
    }

    @Test void capacityWaitsKeepSourceAndQueueAndSuspensionStillCounts() {
        var pcb=admit(1,3);for(int i=2;i<=5;i++) admit(i,1);
        var full=storage.allocateSwap(64);var original=resource(1);var image=memory.readUserBlock(user(1));
        var order=queue.entries();
        assertEquals(new SwapResult.Waiting(SwapResult.Reason.INSUFFICIENT_SWAP),swap.swapOut(1));
        assertSame(original,resource(1));assertEquals(order,queue.entries());assertEquals(image,memory.readUserBlock(user(1)));
        storage.releaseSwap(full);swap.swapOut(1);assertTrue(table.isFull());
        storage.storeProgram("p6",List.of(new IncInstruction()));jobs.add(new Job(6,"p6",JobState.PENDING));
        assertEquals(new AdmissionResult.Waiting(AdmissionResult.Reason.RESIDENT_CAPACITY_REACHED),admission.admit(6));
        var hole=memory.allocateUser(3);var remaining=memory.allocateUser(89);
        var suspended=resource(1);var handle=disk(1);order=queue.entries();
        assertEquals(new SwapResult.Waiting(SwapResult.Reason.INSUFFICIENT_USER_MEMORY),swap.swapIn(1));
        assertSame(suspended,resource(1));assertEquals(image,storage.readSwapBlock(handle));assertEquals(order,queue.entries());
        assertEquals(ProcessState.READY_SUSPENDED,pcb.state());
        memory.release(remaining);memory.release(hole);swap.swapIn(1);
    }

    @Test void failedRestoreReleasesTemporaryUserAndKeepsSwapSource() throws Exception {
        admit(1,3);swap.swapOut(1);var handle=disk(1);var original=resource(1);var image=storage.readSwapBlock(handle);
        var fault=MemoryFaults.install(memory,"user");fault.writeFailure=new IllegalStateException("write failed");
        assertSame(fault.writeFailure,assertThrows(IllegalStateException.class,()->swap.swapIn(1)));
        assertSame(original,resource(1));assertEquals(image,storage.readSwapBlock(handle));assertTrue(queue.entries().isEmpty());
        fault.writeFailure=null;
        var all=memory.allocateUser(96);memory.release(all);swap.swapIn(1);
    }

    @Test void failedSourceReleaseRollsBackPreparedSwapWithoutReorderingReady() throws Exception {
        admit(1,3);admit(2,2);var original=resource(1);var order=queue.entries();
        var fault=MemoryFaults.install(memory,"user");fault.releaseFailure=new IllegalStateException("release rejected");
        assertSame(fault.releaseFailure,assertThrows(IllegalStateException.class,()->swap.swapOut(1)));
        assertSame(original,resource(1));assertEquals(order,queue.entries());assertEquals(3,memory.readUserBlock(user(1)).size());
        var all=storage.allocateSwap(64);storage.releaseSwap(all);
    }
    @Test void multipleImagesAndCorruptionNeverFallBackToOriginalProgram() {
        admit(1,3);admit(2,4);swap.swapOut(1);swap.swapOut(2);
        assertNotEquals(disk(1),disk(2));
        var first=disk(1);var second=disk(2);
        swap.swapIn(2);assertEquals(3,storage.readSwapBlock(first).size());
        assertThrows(RuntimeException.class,()->storage.readSwapBlock(second));
        storage.releaseSwap(first);
        assertThrows(RuntimeException.class,()->swap.swapIn(1));
        assertEquals(ProcessState.READY_SUSPENDED,table.find(1).orElseThrow().state());
        assertInstanceOf(UserImageResidence.Suspended.class,resource(1).residence());
        assertEquals(List.of(2),queue.entries());
    }
    @Test void preparedSwapIsReleasedWhenCommitValidationFails() throws Exception {
        admit(1,3);admit(2,2);var original=resource(1);var order=queue.entries();
        var field=MainMemory.class.getDeclaredField("kernel");field.setAccessible(true);
        var delegate=(MemoryAllocator)field.get(memory);
        var failure=new IllegalStateException("Kernel validation failed before commit");
        field.set(memory,new MemoryAllocator() {
            private int checks;
            public MemoryAllocation allocate(int size) { return delegate.allocate(size); }
            public boolean isActive(MemoryAllocation allocation) {
                if (++checks==2) throw failure;
                return delegate.isActive(allocation);
            }
            public boolean ownsRange(int base,int size) { return delegate.ownsRange(base,size); }
            public void release(MemoryAllocation allocation) { delegate.release(allocation); }
            public void reset() { delegate.reset(); }
        });
        assertSame(failure,assertThrows(IllegalStateException.class,()->swap.swapOut(1)));
        assertSame(original,resource(1));assertEquals(order,queue.entries());
        assertEquals(ProcessState.READY,table.find(1).orElseThrow().state());
        assertEquals(3,memory.readUserBlock(user(1)).size());
        var all=storage.allocateSwap(64);storage.releaseSwap(all);
    }

    @Test void incompleteOrWrongLengthSwapImageIsAnErrorNotCapacityWaiting() {
        var pcb=admit(1,3);swap.swapOut(1);var original=resource(1);var valid=disk(1);
        var wrong=storage.allocateSwap(2);
        registry.replace(1,original,original.withResidence(new UserImageResidence.Suspended(wrong)));
        assertThrows(RuntimeException.class,()->swap.swapIn(1));
        storage.writeSwapBlock(wrong,List.of(new IncInstruction(),new IncInstruction()));
        assertThrows(IllegalStateException.class,()->swap.swapIn(1));
        assertEquals(ProcessState.READY_SUSPENDED,pcb.state());assertTrue(queue.entries().isEmpty());
        assertEquals(3,storage.readSwapBlock(valid).size());
        var all=memory.allocateUser(96);memory.release(all);
    }
}
