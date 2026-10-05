package io.github.rajami1205.osimulator.application.job;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.job.*;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.storage.*;
import io.github.rajami1205.osimulator.model.storage.exception.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JobSubmissionServiceTest {
    private final SecondaryStorage storage = new SecondaryStorage(128, 64);
    private final JobList jobs = new JobList();
    private final JobSubmissionService service = new JobSubmissionService(storage, jobs);

    private ProgramImage program(String name) {
        return new ProgramImage(name, List.of(new LoadInstruction(RegisterName.AX)));
    }

    @Test
    void successfulSubmissionsStoreProgramsAndPublishIndependentSequentialJobs() {
        for (int id = 1; id <= 6; id++) {
            var image = program("p" + id);
            var job = service.submit(image);
            assertEquals(new Job(id, image.logicalName(), JobState.PENDING), job);
            assertSame(job, jobs.find(id).orElseThrow());
            assertEquals(image.instructions(), storage.readProgram(image.logicalName()));
        }
        assertEquals(6, jobs.entries().size());
        var independent = new JobSubmissionService(new SecondaryStorage(128, 64), new JobList());
        assertEquals(1, independent.submit(program("other")).jobId());
    }

    @Test
    void storageFailuresDoNotConsumeIdsOrDeletePreexistingPrograms() {
        var first = service.submit(program("p"));
        assertThrows(StorageException.class, () -> service.submit(program("p")));
        assertEquals(List.of(first), jobs.entries());
        assertEquals(program("p").instructions(), storage.readProgram("p"));
        assertThrows(StorageAllocationException.class, () -> service.submit(new ProgramImage(
                "large", Collections.nCopies(32, new StoreInstruction(RegisterName.BX)))));
        assertTrue(storage.findProgram("large").isEmpty());
        assertEquals(2, service.submit(program("P")).jobId());
    }

    @Test
    void validatesDependenciesNullProgramAndOccupiedIdBeforeStorageMutation() {
        assertThrows(NullPointerException.class, () -> new JobSubmissionService(null, jobs));
        assertThrows(NullPointerException.class, () -> new JobSubmissionService(storage, null));
        assertThrows(NullPointerException.class, () -> service.submit(null));
        jobs.add(new Job(1, "existing", JobState.PENDING));
        assertThrows(IllegalStateException.class, () -> service.submit(program("new")));
        assertTrue(storage.entries().isEmpty());
        assertEquals("existing", jobs.find(1).orElseThrow().programName());
        jobs.remove(1);
        assertEquals(1, service.submit(program("new")).jobId());
    }

    @Test
    void zeroCapacityFailureLeavesIdentityAndJobsUnchanged() {
        var emptyStorage = new SecondaryStorage(128, 127);
        var emptyService = new JobSubmissionService(emptyStorage, jobs);
        assertThrows(StorageAllocationException.class, () -> emptyService.submit(program("p")));
        assertTrue(jobs.entries().isEmpty());
        assertTrue(emptyStorage.entries().isEmpty());
    }

    @Test
    void lastPositiveIntIsUsableThenExhaustionFailsBeforeStorageMutation() throws Exception {
        var counter = JobSubmissionService.class.getDeclaredField("nextJobId");
        counter.setAccessible(true);
        counter.setLong(service, Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, service.submit(program("last")).jobId());
        var before = storage.entries();
        assertThrows(IllegalStateException.class, () -> service.submit(program("overflow")));
        assertEquals(before, storage.entries());
        assertEquals(1, jobs.entries().size());
    }

    @Test
    void publicationFailureRollsBackOnlyNewProgramAndRetriesSameId() throws Exception {
        var prior = service.submit(program("prior"));
        var failure = injectPublicationFailure(null);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.submit(program("new"))));
        assertEquals(List.of(prior), jobs.entries());
        assertTrue(storage.findProgram("new").isEmpty());
        assertEquals(program("prior").instructions(), storage.readProgram("prior"));
        assertSame(EmptyStorageContent.INSTANCE, storage.read(33));
        var retried = service.submit(program("new"));
        assertEquals(2, retried.jobId());
        assertEquals(33, storage.findProgram("new").orElseThrow().startAddress());
    }

    @Test
    void cleanupFailureIsSuppressedOnOriginalPublicationError() throws Exception {
        service.submit(program("prior"));
        var allocationField = SecondaryStorage.class.getDeclaredField("allocations");
        allocationField.setAccessible(true);
        var failure = injectPublicationFailure(() -> {
            try {
                // Fault injection: force release to reject the missing handle during rollback.
                allocationField.set(storage, new HashMap<String, StorageAllocation>());
            } catch (IllegalAccessException exception) {
                throw new AssertionError(exception);
            }
        });
        var thrown = assertThrows(IllegalStateException.class, () -> service.submit(program("new")));
        assertSame(failure, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertInstanceOf(InvalidStorageReleaseException.class, thrown.getSuppressed()[0]);
        assertTrue(jobs.find(2).isEmpty());
    }

    private IllegalStateException injectPublicationFailure(Runnable beforeFailure) throws Exception {
        var failure = new IllegalStateException("Injected Job publication failure");
        var map = new LinkedHashMap<Integer, Job>() {
            private boolean fail = true;
            @Override
            public Set<Map.Entry<Integer, Job>> entrySet() {
                if (fail) {
                    fail = false;
                    if (beforeFailure != null) beforeFailure.run();
                    throw failure;
                }
                return super.entrySet();
            }
        };
        for (var job : jobs.entries()) map.put(job.jobId(), job);
        var field = JobList.class.getDeclaredField("jobs");
        field.setAccessible(true);
        field.set(jobs, map);
        return failure;
    }
}
