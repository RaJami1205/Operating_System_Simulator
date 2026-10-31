package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.memory.PcbField;
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
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.LogicalProcessTimes;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.application.cpu.CpuTopology;
import io.github.rajami1205.osimulator.application.cpu.CpuNode;
import io.github.rajami1205.osimulator.model.scheduling.ReadyQueue;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import java.util.Objects;
import java.util.Optional;

/** Admisión transaccional de una sesión; no selecciona ni modifica la CPU activa. */
public final class ProcessAdmissionService {
    private final JobList jobs;
    private final SecondaryStorage storage;
    private final PcbKernelSynchronizer pcbSynchronizer;
    private final MainMemory memory;
    private final ProcessTable processes;
    private final ProgramLoader loader;
    private final ReadyQueue readyQueue;
    private final ProcessResourceRegistry resources;
    private final CpuTopology topology;
    private long nextProcessId = 1;

    /** Recibe los recursos canónicos de sesión y el loader; inicia PID sin apropiarse del CPU. */
    public ProcessAdmissionService(JobList jobs, SecondaryStorage storage, MainMemory memory,
            ProcessTable processes, ProgramLoader loader, ReadyQueue readyQueue, ProcessResourceRegistry resources) {
        this(jobs, storage, memory, processes, loader, readyQueue, resources, null);
    }

    public ProcessAdmissionService(CpuTopology topology, JobList jobs, SecondaryStorage storage, MainMemory memory,
            ProcessTable processes, ProgramLoader loader, ProcessResourceRegistry resources) {
        this(jobs, storage, memory, processes, loader, Objects.requireNonNull(topology).node(CpuId.ZERO).readyQueue(), resources, topology);
    }

    private ProcessAdmissionService(JobList jobs, SecondaryStorage storage, MainMemory memory,
            ProcessTable processes, ProgramLoader loader, ReadyQueue readyQueue, ProcessResourceRegistry resources, CpuTopology topology) {
        this.topology = topology;
        if (topology != null) topology.requireSharedResources(processes, memory, resources);
        this.resources = Objects.requireNonNull(resources, "resources must not be null");
        this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.memory = Objects.requireNonNull(memory, "memory must not be null");
        this.pcbSynchronizer = new PcbKernelSynchronizer(memory, resources);
        this.processes = Objects.requireNonNull(processes, "processes must not be null");
        this.loader = Objects.requireNonNull(loader, "loader must not be null");
        this.readyQueue = Objects.requireNonNull(readyQueue, "readyQueue must not be null");
        readyQueue.requireBinding(CpuId.ZERO, processes);
    }

    /**
     * Intenta admitir el Job PENDING con PCB Kernel, programa USER y READY; devuelve Waiting sólo por
     * capacidad ordinaria y revierte publicaciones ante fallo.
     */
    public AdmissionResult admit(int jobId) {
        return admit(jobId, 0);
    }

    /** El coordinador suministra la frontera lógica; ninguna ruta admite llegadas futuras. */
    public AdmissionResult admit(int jobId, long currentTime) {
        if (currentTime < 0) throw new IllegalArgumentException("Negative admission time");
        Job job = jobs.find(jobId).orElseThrow(() -> new IllegalArgumentException("Unknown Job ID: " + jobId));
        if (job.state() != JobState.PENDING) throw new IllegalStateException("Job is already admitted: " + jobId);
        if (job.arrivalTime() > currentTime) throw new IllegalStateException("Job has not arrived: " + jobId);
        if (processes.isFull()) return new AdmissionResult.Waiting(AdmissionResult.Reason.RESIDENT_CAPACITY_REACHED);
        CpuId affinity = topology == null ? CpuId.ZERO : topology.leastLoaded().map(CpuNode::id).orElse(null);
        if (affinity == null || processes.countForCpu(affinity) >= ProcessTable.MAX_ADMITTED_PROCESSES) {
            return new AdmissionResult.Waiting(AdmissionResult.Reason.RESIDENT_CAPACITY_REACHED);
        }
        ReadyQueue targetQueue = topology == null ? readyQueue : topology.node(affinity).readyQueue();
        if (nextProcessId > Integer.MAX_VALUE) throw new IllegalStateException("Process IDs exhausted");
        int pid = (int) nextProcessId;
        if (processes.find(pid).isPresent() || resources.find(pid).isPresent()) {
            throw new IllegalStateException("Next Process ID is already resident: " + pid);
        }
        var program = storage.readProgram(job.programName());
        MemoryAllocation kernel;
        try {
            // El tamaño fijo es positivo: un fallo de allocation aquí indica capacidad insuficiente.
            kernel = memory.allocateKernel(PcbField.PCB_SIZE);
        } catch (MemoryAllocationException shortage) {
            return new AdmissionResult.Waiting(AdmissionResult.Reason.INSUFFICIENT_KERNEL_MEMORY);
        }

        ProgramLoadResult loaded = null;
        ProcessControlBlock previous = null;
        Optional<PcbAddress> previousLink = Optional.empty();
        boolean resourcesPublished = false;
        boolean processPublished = false;
        boolean linkChanged = false;
        boolean enqueued = false;
        try {
            loaded = loader.loadWithAllocation(memory, pid, program,
                    LogicalProcessTimes.admitted(job.arrivalTime(), currentTime), affinity);
            memory.materializePcb(kernel, loaded.pcb());
            var address = new PcbAddress(kernel.base());
            previous = processes.last().orElse(null);
            if (previous != null) previousLink = previous.nextPcbAddress();
            var result = new AdmissionResult.Admitted(pid);
            resources.register(pid, new ProcessResources(kernel, address, new UserImageResidence.Resident(loaded.userAllocation())));
            resourcesPublished = true;
            processes.register(loaded.pcb());
            processPublished = true;
            if (previous != null) {
                previous.setNextPcbAddress(Optional.of(address));
                linkChanged = true;
                pcbSynchronizer.sync(previous);
            }
            if (loaded.pcb().state() != ProcessState.READY) {
                throw new IllegalStateException("Admission requires a READY PCB: " + pid);
            }
            targetQueue.enqueue(pid);
            enqueued = true;
            // Copia antes de publicar; tras este commit no quedan operaciones de preparación que puedan fallar.
            jobs.markAdmitted(jobId);
            nextProcessId++;
            return result;
        } catch (RuntimeException | Error failure) {
            if (enqueued) cleanup(failure, () -> targetQueue.remove(pid));
            if (linkChanged) {
                var tail = previous;
                var oldLink = previousLink;
                cleanup(failure, () -> {
                    tail.setNextPcbAddress(oldLink);
                    pcbSynchronizer.sync(tail);
                });
            }
            if (processPublished) cleanup(failure, () -> processes.remove(pid));
            if (resourcesPublished) cleanup(failure, () -> resources.remove(pid));
            if (loaded != null) {
                var user = loaded.userAllocation();
                cleanup(failure, () -> memory.release(user));
            }
            cleanup(failure, () -> memory.release(kernel));
            // El Loader envuelve con esta causa sólo fallos de allocateUser, tras validar el tamaño.
            // Un rollback fallido no debe ocultarse como un resultado Waiting ordinario.
            if (loaded == null && failure instanceof ProgramLoadException
                    && failure.getCause() instanceof MemoryAllocationException
                    && failure.getSuppressed().length == 0
                    && failure.getCause().getSuppressed().length == 0) {
                return new AdmissionResult.Waiting(AdmissionResult.Reason.INSUFFICIENT_USER_MEMORY);
            }
            throw failure;
        }
    }

    /**
     * Ejecuta una acción de rollback y conserva cualquier fallo secundario como suppressed del diagnóstico
     * original.
     */
    private static void cleanup(Throwable original, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            if (failure != original) original.addSuppressed(failure);
        }
    }
}
