package io.github.rajami1205.osimulator.application.job;

import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.job.*;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import io.github.rajami1205.osimulator.model.scheduling.ReadyQueue;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JobSchedulerTest {
    @Test
    void attemptsOnlyFirstPendingInSubmissionOrderAndNeverBypassesWaitingJob() {
        var jobs = new JobList();
        var storage = new SecondaryStorage(512, 64);
        var memory = new MainMemory(new MemoryConfiguration(128, 32));
        var table = new ProcessTable();
        var queue = new ReadyQueue();
        var admission = new ProcessAdmissionService(jobs, storage, memory, table, new ProgramLoader(), queue);
        var scheduler = new JobScheduler(jobs, admission);
        assertTrue(scheduler.attemptNextAdmission().isEmpty());
        var instruction = new LoadInstruction(RegisterName.AX);
        storage.storeProgram("large", Collections.nCopies(10, instruction));
        storage.storeProgram("small", List.of(instruction));
        jobs.add(new Job(20, "large", JobState.PENDING));
        jobs.add(new Job(1, "small", JobState.PENDING));
        var occupied = memory.allocateUser(90);
        assertEquals(Optional.of(new AdmissionResult.Waiting(AdmissionResult.Reason.INSUFFICIENT_USER_MEMORY)), scheduler.attemptNextAdmission());
        assertTrue(table.entries().isEmpty());
        assertTrue(queue.entries().isEmpty());
        assertEquals(JobState.PENDING, jobs.find(1).orElseThrow().state());
        memory.release(occupied);
        assertEquals(Optional.of(new AdmissionResult.Admitted(1)), scheduler.attemptNextAdmission());
        assertEquals(1, table.size());
        assertEquals(JobState.ADMITTED, jobs.find(20).orElseThrow().state());
        assertEquals(JobState.PENDING, jobs.find(1).orElseThrow().state());
        assertEquals(Optional.of(new AdmissionResult.Admitted(2)), scheduler.attemptNextAdmission());
        assertEquals(List.of(1, 2), queue.entries());
        assertTrue(scheduler.attemptNextAdmission().isEmpty());
    }
}
