package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.memory.exception.IncompatibleProgramException;
import io.github.rajami1205.osimulator.model.storage.*;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.job.*;
import io.github.rajami1205.osimulator.model.scheduling.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PagingTransactionsTest {
    @Test void blockedSwapPreservesGenericStateAndRejectsForeignPidPublication() {
        submit(1, 5); admission.admit(1); var pcb = table.find(1).orElseThrow();
        var resource = registry.find(1).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> registry.register(2, resource));
        assertTrue(registry.find(2).isEmpty());
        queue.remove(1); pcb.changeState(ProcessState.BLOCKED);
        new PcbKernelSynchronizer(memory, registry).sync(pcb);
        swap.swapOut(1); assertEquals(ProcessState.BLOCKED_SUSPENDED, pcb.state());
        swap.swapIn(1); assertEquals(ProcessState.BLOCKED, pcb.state()); assertTrue(queue.entries().isEmpty());
        assertEquals(4096, pcb.programCounter()); assertEquals(5, image(1).size());
    }
    private final MainMemory memory = new MainMemory(new MemoryConfiguration(160, 140),
            UserAllocationStrategy.FIRST_FIT, MemoryMode.PAGING, Optional.empty());
    private final SecondaryStorage storage = new SecondaryStorage(512, 64);
    private final ProcessTable table = new ProcessTable();
    private final ProcessResourceRegistry registry = new ProcessResourceRegistry();
    private final ReadyQueue queue = new ReadyQueue();
    private final JobList jobs = new JobList();
    private final ProcessAdmissionService admission = new ProcessAdmissionService(jobs, storage, memory, table, new ProgramLoader(), queue, registry);
    private final ProcessSwapService swap = new ProcessSwapService(memory, storage, table, registry, queue);
    private void submit(int id, int size) {
        storage.storeProgram("p" + id, Collections.nCopies(size, new IncInstruction())); jobs.add(new Job(id, "p" + id, JobState.PENDING));
    }
    private PageTable image(int pid) { return ((UserImageResidence.Paged) registry.find(pid).orElseThrow().residence()).table(); }
    @Test void lateAdmissionFailureRollsBackFramesRegistryKernelLinksQueueAndPid() throws Exception {
        submit(1, 3); admission.admit(1); submit(2, 5);
        var before = memory.frames(); var pcb = table.find(1).orElseThrow(); var link = pcb.nextPcbAddress();
        var replacement = new LinkedHashMap<Integer, Job>() {
            boolean fail = true;
            @Override public Set<Map.Entry<Integer, Job>> entrySet() {
                if (fail) { fail = false; throw new IllegalStateException("Late Job publication failure"); }
                return super.entrySet();
            }
        };
        for (var job : jobs.entries()) replacement.put(job.jobId(), job);
        var field = JobList.class.getDeclaredField("jobs"); field.setAccessible(true); field.set(jobs, replacement);
        assertThrows(IllegalStateException.class, () -> admission.admit(2));
        assertEquals(before, memory.frames()); assertEquals(link, pcb.nextPcbAddress());
        assertTrue(table.find(2).isEmpty()); assertTrue(registry.find(2).isEmpty());
        assertEquals(List.of(1), queue.entries()); assertEquals(JobState.PENDING, jobs.find(2).orElseThrow().state());
        assertEquals(new AdmissionResult.Admitted(2), admission.admit(2));
        assertEquals(28, registry.find(2).orElseThrow().kernel().base());
        memory.validatePcbAllocation(registry.find(1).orElseThrow().kernel(), pcb);
    }
    @Test void permanentIncompatibilityIsNotWaitingButTransientFrameShortageIs() {
        submit(1, 21); assertThrows(IncompatibleProgramException.class, () -> admission.admit(1));
        assertTrue(registry.entries().isEmpty());
        submit(2, 17); assertEquals(new AdmissionResult.Admitted(1), admission.admit(2));
        submit(3, 1); var before = memory.frames();
        assertEquals(new AdmissionResult.Waiting(AdmissionResult.Reason.INSUFFICIENT_USER_MEMORY), admission.admit(3));
        assertEquals(before, memory.frames()); assertEquals(JobState.PENDING, jobs.find(3).orElseThrow().state());
    }
    @Test void swapRoundTripUsesNewScatteredFramesAndPreservesBackingOnFailedRestore() throws Exception {
        submit(1, 7); admission.admit(1); var pcb = table.find(1).orElseThrow(); pcb.setProgramCounter(4099);
        var old = image(1); var instructions = memory.readImage(old); var context = pcb.cpuContext();
        swap.swapOut(1);
        var suspended = registry.find(1).orElseThrow();
        var backing = ((UserImageResidence.Suspended) suspended.residence()).allocation();
        assertEquals(instructions, storage.readSwapBlock(backing));
        assertTrue(memory.frames().stream().allMatch(f -> f.handle().isEmpty()));
        assertThrows(RuntimeException.class, () -> memory.readImage(old));
        var a = memory.reserveImage(90, 4); memory.confirmUser(a);
        var b = memory.reserveImage(91, 4); memory.confirmUser(b);
        var c = memory.reserveImage(92, 4); memory.confirmUser(c);
        var d = memory.reserveImage(93, 8); memory.confirmUser(d);
        assertEquals(new SwapResult.Waiting(SwapResult.Reason.INSUFFICIENT_USER_MEMORY), swap.swapIn(1));
        assertSame(suspended, registry.find(1).orElseThrow());
        memory.release(a.image()); memory.release(c.image());
        var contextField = ProcessControlBlock.class.getDeclaredField("cpuContext"); contextField.setAccessible(true);
        contextField.set(pcb, context.withProgramCounter(5000));
        var before = memory.frames();
        assertThrows(RuntimeException.class, () -> swap.swapIn(1));
        assertEquals(before, memory.frames()); assertEquals(instructions, storage.readSwapBlock(backing));
        assertSame(suspended, registry.find(1).orElseThrow()); assertTrue(queue.entries().isEmpty());
        pcb.replaceCpuContext(context);
        assertEquals(new SwapResult.Completed(), swap.swapIn(1));
        var fresh = image(1); assertNotEquals(old.imageId(), fresh.imageId());
        assertEquals(List.of(0, 2), fresh.frames().stream().map(h -> h.frame().id()).toList());
        assertEquals(instructions, memory.readImage(fresh)); assertEquals(context, pcb.cpuContext());
        assertEquals(List.of(1), queue.entries()); assertEquals(ProcessState.READY, pcb.state());
        memory.validatePcbAllocation(registry.find(1).orElseThrow().kernel(), pcb);
    }
}
