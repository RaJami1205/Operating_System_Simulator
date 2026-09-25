package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.program.ProgramLoadResult;
import io.github.rajami1205.osimulator.application.program.exception.ProgramLoadException;
import io.github.rajami1205.osimulator.model.job.Job;
import io.github.rajami1205.osimulator.model.job.JobList;
import io.github.rajami1205.osimulator.model.job.JobState;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.memory.MemoryAllocation;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryAllocationException;
import io.github.rajami1205.osimulator.model.process.PcbAddress;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Admisión transaccional de una sesión; no selecciona ni modifica la CPU activa. */
public final class ProcessAdmissionService {
    private final JobList jobs;
    private final SecondaryStorage storage;
    private final MainMemory memory;
    private final ProcessTable processes;
    private final ProgramLoader loader;
    private final Map<Integer, ResidentResources> residents = new HashMap<>();
    private long nextProcessId = 1;

    private record ResidentResources(MemoryAllocation user, MemoryAllocation kernel, PcbAddress address) {}

    public ProcessAdmissionService(JobList jobs, SecondaryStorage storage, MainMemory memory,
            ProcessTable processes, ProgramLoader loader) {
        this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.memory = Objects.requireNonNull(memory, "memory must not be null");
        this.processes = Objects.requireNonNull(processes, "processes must not be null");
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
    }

    public AdmissionResult admit(int jobId) {
        Job job = jobs.find(jobId).orElseThrow(() -> new IllegalArgumentException("Unknown Job ID: " + jobId));
        if (job.state() != JobState.PENDING) throw new IllegalStateException("Job is already admitted: " + jobId);
        if (processes.isFull()) return new AdmissionResult.Waiting(AdmissionResult.Reason.RESIDENT_CAPACITY_REACHED);
        if (nextProcessId > Integer.MAX_VALUE) throw new IllegalStateException("Process IDs exhausted");
        int pid = (int) nextProcessId;
        if (processes.find(pid).isPresent() || residents.containsKey(pid)) {
            throw new IllegalStateException("Next Process ID is already resident: " + pid);
        }
        var program = storage.readProgram(job.programName());
        MemoryAllocation kernel;
        try {
            // Positive fixed size: an allocation failure here means insufficient capacity.
            kernel = memory.allocateKernel(1);
        } catch (MemoryAllocationException shortage) {
            return new AdmissionResult.Waiting(AdmissionResult.Reason.INSUFFICIENT_KERNEL_MEMORY);
        }

        ProgramLoadResult loaded = null;
        ProcessControlBlock previous = null;
        Optional<PcbAddress> previousLink = Optional.empty();
        boolean resourcesPublished = false;
        boolean processPublished = false;
        boolean linkChanged = false;
        try {
            loaded = loader.loadWithAllocation(memory, pid, program);
            memory.writePcb(kernel, 0, loaded.pcb());
            var address = new PcbAddress(kernel.base());
            previous = processes.last().orElse(null);
            if (previous != null) previousLink = previous.nextPcbAddress();
            var result = new AdmissionResult.Admitted(pid);
            residents.put(pid, new ResidentResources(loaded.userAllocation(), kernel, address));
            resourcesPublished = true;
            processes.register(loaded.pcb());
            processPublished = true;
            if (previous != null) {
                previous.setNextPcbAddress(Optional.of(address));
                linkChanged = true;
            }
            // Copy-before-publication; no potentially failing work follows this commit.
            jobs.markAdmitted(jobId);
            nextProcessId++;
            return result;
        } catch (RuntimeException | Error failure) {
            if (linkChanged) {
                var tail = previous;
                var oldLink = previousLink;
                cleanup(failure, () -> tail.setNextPcbAddress(oldLink));
            }
            if (processPublished) cleanup(failure, () -> processes.remove(pid));
            if (resourcesPublished) cleanup(failure, () -> residents.remove(pid));
            if (loaded != null) {
                var user = loaded.userAllocation();
                cleanup(failure, () -> memory.release(user));
            }
            cleanup(failure, () -> memory.release(kernel));
            // Loader wraps only allocateUser failures with this cause, after validating size.
            // Never hide a failed rollback behind an ordinary Waiting result.
            if (loaded == null && failure instanceof ProgramLoadException
                    && failure.getCause() instanceof MemoryAllocationException
                    && failure.getSuppressed().length == 0
                    && failure.getCause().getSuppressed().length == 0) {
                return new AdmissionResult.Waiting(AdmissionResult.Reason.INSUFFICIENT_USER_MEMORY);
            }
            throw failure;
        }
    }

    private static void cleanup(Throwable original, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (failure != original) original.addSuppressed(failure);
        }
    }
}
