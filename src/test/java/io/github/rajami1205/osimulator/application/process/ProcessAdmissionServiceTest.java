package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.job.*;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import io.github.rajami1205.osimulator.model.storage.exception.StorageException;
import io.github.rajami1205.osimulator.support.MemoryFaults;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessAdmissionServiceTest {
    private final MainMemory memory = new MainMemory(new MemoryConfiguration(128, 32));
    private final SecondaryStorage storage = new SecondaryStorage(512, 64);
    private final JobList jobs = new JobList();
    private final ProcessTable table = new ProcessTable();
    private final ProcessAdmissionService service = new ProcessAdmissionService(jobs, storage, memory, table, new ProgramLoader());
    private final Instruction instruction = new LoadInstruction(RegisterName.AX);

    private void submit(int jobId, int length) {
        storage.storeProgram("p" + jobId, Collections.nCopies(length, instruction));
        jobs.add(new Job(jobId, "p" + jobId, JobState.PENDING));
    }

    @Test
    void admitsCanonicalReadyPcbsWithIndependentIdsAndPhysicalKernelLinks() throws Exception {
        for (int i = 1; i <= 3; i++) {
            submit(i * 10, 2);
            assertEquals(new AdmissionResult.Admitted(i), service.admit(i * 10));
            var pcb = table.find(i).orElseThrow();
            assertEquals(ProcessState.READY, pcb.state());
            assertEquals(0, pcb.programCounter());
            assertEquals(new ProcessMemoryBounds(32 + (i - 1) * 2, 2), pcb.memoryBounds());
            assertSame(pcb, assertInstanceOf(PcbContent.class, memory.read(i - 1)).pcb());
            assertSame(instruction, memory.readInstruction(pcb.memoryBounds(), 0));
            assertSame(instruction, memory.readInstruction(pcb.memoryBounds(), 1));
            assertEquals(Collections.nCopies(2, instruction), storage.readProgram("p" + i * 10));
            assertEquals(JobState.ADMITTED, jobs.find(i * 10).orElseThrow().state());
            if (i > 1) assertEquals(Optional.of(new PcbAddress(i - 1)), table.find(i - 1).orElseThrow().nextPcbAddress());
            assertTrue(pcb.nextPcbAddress().isEmpty());
            var resource = resources().get(i);
            assertEquals(new PcbAddress(i - 1), resourcePart(resource, "address"));
            assertEquals(1, ((MemoryAllocation) resourcePart(resource, "kernel")).size());
        }
        assertTrue(memory.isEmpty(3));
        // Actual release proves these are the original active tokens, not reconstructed bounds.
        for (var resource : resources().values()) {
            memory.release((MemoryAllocation) resourcePart(resource, "user"));
            memory.release((MemoryAllocation) resourcePart(resource, "kernel"));
        }
        assertEquals(32, memory.allocateUser(96).base());
        assertEquals(0, memory.allocateKernel(32).base());
    }

    @Test
    void capacityWaitsWithoutChangingJobsStorageOrIds() throws Exception {
        for (int i = 1; i <= ProcessTable.MAX_ADMITTED_PROCESSES + 1; i++) submit(i, 1);
        for (int i = 1; i <= ProcessTable.MAX_ADMITTED_PROCESSES; i++) service.admit(i);
        var before = storage.entries();
        long next = counter();
        assertEquals(new AdmissionResult.Waiting(AdmissionResult.Reason.RESIDENT_CAPACITY_REACHED),
                service.admit(ProcessTable.MAX_ADMITTED_PROCESSES + 1));
        assertEquals(next, counter());
        assertEquals(JobState.PENDING, jobs.entries().getLast().state());
        assertEquals(before, storage.entries());
        assertEquals(ProcessTable.MAX_ADMITTED_PROCESSES, resources().size());
    }

    @Test
    void kernelShortageWaitsAndRetryUsesSameId() {
        submit(20, 1);
        var occupied = memory.allocateKernel(32);
        assertEquals(new AdmissionResult.Waiting(AdmissionResult.Reason.INSUFFICIENT_KERNEL_MEMORY), service.admit(20));
        assertTrue(table.entries().isEmpty());
        assertEquals(JobState.PENDING, jobs.find(20).orElseThrow().state());
        assertTrue(memory.isEmpty(32));
        memory.release(occupied);
        assertEquals(new AdmissionResult.Admitted(1), service.admit(20));
    }

    @Test
    void fragmentedUserShortageReleasesKernelAndDoesNotConsumeId() {
        submit(8, 40);
        var left = memory.allocateUser(32);
        var middle = memory.allocateUser(32);
        var right = memory.allocateUser(32);
        memory.release(left);
        memory.release(right);
        assertEquals(new AdmissionResult.Waiting(AdmissionResult.Reason.INSUFFICIENT_USER_MEMORY), service.admit(8));
        assertEquals(JobState.PENDING, jobs.find(8).orElseThrow().state());
        var kernel = memory.allocateKernel(32);
        assertEquals(0, kernel.base());
        memory.release(kernel);
        memory.release(middle);
        assertEquals(new AdmissionResult.Admitted(1), service.admit(8));
    }

    @Test
    void invalidJobsMissingProgramAndPidCollisionAreErrorsWithoutAcquisition() {
        assertThrows(IllegalArgumentException.class, () -> service.admit(0));
        assertThrows(IllegalArgumentException.class, () -> service.admit(99));
        jobs.add(new Job(9, "missing", JobState.PENDING));
        assertThrows(StorageException.class, () -> service.admit(9));
        submit(10, 1);
        table.register(new ProcessControlBlock(1, 32, 1));
        assertThrows(IllegalStateException.class, () -> service.admit(10));
        table.remove(1);
        assertEquals(new AdmissionResult.Admitted(1), service.admit(10));
        assertThrows(IllegalStateException.class, () -> service.admit(10));
        assertEquals(1, table.size());
    }

    @Test
    void detectsPidExhaustionBeforeAcquiringResources() throws Exception {
        submit(1, 1);
        submit(2, 1);
        var field = ProcessAdmissionService.class.getDeclaredField("nextProcessId");
        field.setAccessible(true);
        field.setLong(service, Integer.MAX_VALUE);
        assertEquals(new AdmissionResult.Admitted(Integer.MAX_VALUE), service.admit(1));
        assertThrows(IllegalStateException.class, () -> service.admit(2));
        assertEquals(JobState.PENDING, jobs.find(2).orElseThrow().state());
        assertEquals(1, table.size());
        assertEquals(1, memory.allocateKernel(31).base());
        assertEquals(33, memory.allocateUser(95).base());
    }

    @Test
    void lateJobFailureRestoresPreviousLinkAndEveryResourceThenRetriesSamePid() throws Exception {
        submit(10, 2);
        submit(20, 3);
        service.admit(10);
        var first = table.find(1).orElseThrow();
        var oldLink = Optional.of(new PcbAddress(17));
        first.setNextPcbAddress(oldLink);
        var failure = failJobPublication(null);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.admit(20)));
        assertEquals(oldLink, first.nextPcbAddress());
        assertEquals(List.of(first), table.entries());
        assertEquals(Set.of(1), resources().keySet());
        assertEquals(JobState.PENDING, jobs.find(20).orElseThrow().state());
        assertEquals(Collections.nCopies(3, instruction), storage.readProgram("p20"));
        assertTrue(memory.isEmpty(1));
        assertTrue(memory.isEmpty(34));
        assertEquals(new AdmissionResult.Admitted(2), service.admit(20));
        assertEquals(Optional.of(new PcbAddress(1)), first.nextPcbAddress());
        assertEquals(34, table.find(2).orElseThrow().memoryBounds().base());
    }

    @Test
    void userWriteFailureIsNotWaitingAndLoaderAloneReleasesUser() throws Exception {
        submit(1, 1);
        var faults = MemoryFaults.install(memory, "user");
        var failure = new IllegalStateException("write failed");
        faults.writeFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.admit(1)));
        assertEquals(1, faults.releases);
        assertEquals(0, memory.allocateKernel(32).base());
        assertEquals(32, memory.allocateUser(96).base());
        assertTrue(resources().isEmpty());
        assertEquals(1, counter());
    }

    @Test
    void kernelWriteFailureRollsBackTransferredUserOwnership() throws Exception {
        submit(1, 1);
        var user = MemoryFaults.install(memory, "user");
        var kernel = MemoryFaults.install(memory, "kernel");
        var failure = new IllegalStateException("kernel write failed");
        kernel.writeFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.admit(1)));
        assertEquals(1, user.releases);
        assertEquals(1, kernel.releases);
        assertTrue(table.entries().isEmpty());
        assertTrue(resources().isEmpty());
        assertTrue(memory.isEmpty(32));
        assertEquals(32, memory.allocateUser(96).base());
        assertEquals(0, memory.allocateKernel(32).base());
    }

    @Test
    void cleanupFailuresAreSuppressedAndDoNotPreventRemainingCleanup() throws Exception {
        submit(1, 1);
        var user = MemoryFaults.install(memory, "user");
        var kernel = MemoryFaults.install(memory, "kernel");
        user.releaseFailure = new IllegalStateException("user cleanup failed");
        kernel.releaseFailure = new IllegalStateException("kernel cleanup failed");
        var failure = failJobPublication(null);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.admit(1)));
        assertArrayEquals(new Throwable[]{user.releaseFailure, kernel.releaseFailure}, failure.getSuppressed());
        assertEquals(1, user.releases);
        assertEquals(1, kernel.releases);
        assertTrue(table.entries().isEmpty());
        assertTrue(resources().isEmpty());
        assertEquals(JobState.PENDING, jobs.find(1).orElseThrow().state());
        assertEquals(1, counter());
    }

    @Test
    void rejectedTablePublicationRollsBackResidentRegistryAndBothAllocations() throws Exception {
        submit(1, 1);
        var failure = new IllegalStateException("table publication failed");
        var field = ProcessTable.class.getDeclaredField("processes");
        field.setAccessible(true);
        field.set(table, new LinkedHashMap<Integer, ProcessControlBlock>() {
            @Override public boolean containsKey(Object key) { throw failure; }
        });
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.admit(1)));
        assertTrue(table.entries().isEmpty());
        assertTrue(resources().isEmpty());
        assertTrue(memory.isEmpty(0));
        assertTrue(memory.isEmpty(32));
        assertEquals(0, memory.allocateKernel(32).base());
        assertEquals(32, memory.allocateUser(96).base());
        assertEquals(JobState.PENDING, jobs.find(1).orElseThrow().state());
        assertEquals(1, counter());
    }

    @Test
    void loaderCleanupFailureIsPreservedWithoutSecondUserRelease() throws Exception {
        submit(1, 1);
        var user = MemoryFaults.install(memory, "user");
        user.writeFailure = new IllegalStateException("write failed");
        user.releaseFailure = new IllegalStateException("release failed");
        var failure = assertThrows(IllegalStateException.class, () -> service.admit(1));
        assertSame(user.writeFailure, failure);
        assertArrayEquals(new Throwable[]{user.releaseFailure}, failure.getSuppressed());
        assertEquals(1, user.releases);
        assertEquals(0, memory.allocateKernel(32).base());
        assertEquals(1, counter());
    }

    @Test
    void resultVariantsRejectInvalidPayloads() {
        assertThrows(IllegalArgumentException.class, () -> new AdmissionResult.Admitted(0));
        assertThrows(NullPointerException.class, () -> new AdmissionResult.Waiting(null));
    }

    @Test
    void shortageWithFailedKernelCleanupIsAnErrorNotWaiting() throws Exception {
        submit(1, 1);
        memory.allocateUser(96);
        var kernel = MemoryFaults.install(memory, "kernel");
        kernel.releaseFailure = new IllegalStateException("kernel cleanup failed");
        var failure = assertThrows(io.github.rajami1205.osimulator.application.program.exception.ProgramLoadException.class,
                () -> service.admit(1));
        assertArrayEquals(new Throwable[]{kernel.releaseFailure}, failure.getSuppressed());
        assertEquals(JobState.PENDING, jobs.find(1).orElseThrow().state());
    }

    private IllegalStateException failJobPublication(Runnable beforeFailure) throws Exception {
        var failure = new IllegalStateException("Job publication failed");
        var replacement = new LinkedHashMap<Integer, Job>() {
            private boolean fail = true;
            @Override public Set<Map.Entry<Integer, Job>> entrySet() {
                if (fail) {
                    fail = false;
                    if (beforeFailure != null) beforeFailure.run();
                    throw failure;
                }
                return super.entrySet();
            }
        };
        for (var job : jobs.entries()) replacement.put(job.jobId(), job);
        var field = JobList.class.getDeclaredField("jobs");
        field.setAccessible(true);
        field.set(jobs, replacement);
        return failure;
    }

    private Map<?, ?> resources() throws Exception {
        var field = ProcessAdmissionService.class.getDeclaredField("residents");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(service);
    }

    private Object resourcePart(Object resource, String name) throws Exception {
        var method = resource.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(resource);
    }

    private long counter() throws Exception {
        var field = ProcessAdmissionService.class.getDeclaredField("nextProcessId");
        field.setAccessible(true);
        return field.getLong(service);
    }
}
