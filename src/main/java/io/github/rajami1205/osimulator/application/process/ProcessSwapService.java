package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryAllocationException;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.scheduling.ReadyQueue;
import io.github.rajami1205.osimulator.model.storage.*;
import io.github.rajami1205.osimulator.model.storage.exception.StorageAllocationException;
import java.util.Objects;
import io.github.rajami1205.osimulator.application.cpu.CpuTopology;
import io.github.rajami1205.osimulator.model.cpu.CpuId;

/**
 * Transfiere imágenes completas entre USER y VIRTUAL_MEMORY sin paging ni selección automática de víctimas. Conserva
 * el PCB Kernel y su contexto; el caller excluye al owner activo.
 */
public final class ProcessSwapService {
    private final PcbKernelSynchronizer pcbSynchronizer;
    private final MainMemory memory;
    private final SecondaryStorage storage;
    private final ProcessTable processes;
    private final ProcessResourceRegistry resources;
    private final ReadyQueue queue;
    private final CpuTopology topology;

    /** Asocia memoria, storage, PCBs, registry y ReadyQueue no nulos de una misma sesión. */
    public ProcessSwapService(MainMemory memory, SecondaryStorage storage, ProcessTable processes,
            ProcessResourceRegistry resources, ReadyQueue queue) {
        this(memory, storage, processes, resources, queue, null);
    }

    public ProcessSwapService(CpuTopology topology, MainMemory memory, SecondaryStorage storage,
            ProcessTable processes, ProcessResourceRegistry resources) {
        this(memory, storage, processes, resources, Objects.requireNonNull(topology).node(CpuId.ZERO).readyQueue(), topology);
    }

    private ProcessSwapService(MainMemory memory, SecondaryStorage storage, ProcessTable processes,
            ProcessResourceRegistry resources, ReadyQueue queue, CpuTopology topology) {
        this.topology = topology;
        if (topology != null) topology.requireSharedResources(processes, memory, resources);
        this.memory = Objects.requireNonNull(memory);
        this.pcbSynchronizer = new PcbKernelSynchronizer(memory, resources);
        this.storage = Objects.requireNonNull(storage);
        this.processes = Objects.requireNonNull(processes);
        this.resources = Objects.requireNonNull(resources);
        this.queue = Objects.requireNonNull(queue);
        queue.requireBinding(CpuId.ZERO, processes);
    }

    /**
     * Copia una imagen READY/BLOCKED a VIRTUAL_MEMORY antes de liberar USER; devuelve Waiting por capacidad y
     * revierte la reserva destino ante fallo.
     */
    public SwapResult swapOut(int processId) {
        var pcb = process(processId);
        var queue = queueFor(pcb);
        var original = canonicalResources(pcb);
        if (!(original.residence() instanceof UserImageResidence.Resident resident)
                || (pcb.state() != ProcessState.READY && pcb.state() != ProcessState.BLOCKED)) {
            throw new IllegalStateException("Swap-out requires an eligible resident READY/BLOCKED process");
        }
        boolean ready = pcb.state() == ProcessState.READY;
        validateQueue(processId, ready);
        var user = resident.allocation();
        if (user.base() != pcb.memoryBounds().base() || user.size() != pcb.instructionCount()) {
            throw new IllegalStateException("Resident image and PCB bounds disagree");
        }
        var image = memory.readUserBlock(user);
        StorageAllocation swap;
        try {
            swap = storage.allocateSwap(image.size());
        } catch (StorageAllocationException shortage) {
            return new SwapResult.Waiting(SwapResult.Reason.INSUFFICIENT_SWAP);
        }
        ProcessResources replacement;
        try {
            storage.writeSwapBlock(swap, image);
            replacement = original.withResidence(new UserImageResidence.Suspended(swap));
            validateCommit(pcb, original, ready);
            // Release valida antes de modificar; después de este punto no queda preparación que pueda fallar.
            memory.release(user);
        } catch (RuntimeException | Error failure) {
            cleanup(failure, () -> storage.releaseSwap(swap));
            throw failure;
        }
        resources.replace(processId, original, replacement);
        pcb.changeState(ready ? ProcessState.READY_SUSPENDED : ProcessState.BLOCKED_SUSPENDED);
        if (ready) queue.remove(processId);
        if (ready && topology != null) topology.node(pcb.cpuAffinity()).suspendedReadyQueue().enqueue(processId);
        pcbSynchronizer.sync(pcb);
        return new SwapResult.Completed();
    }

    /**
     * Carga la imagen suspended en una nueva reserva USER, relocaliza Base y libera VIRTUAL_MEMORY;
     * conserva el contexto lógico completo y devuelve Waiting por capacidad.
     */
    public SwapResult swapIn(int processId) {
        var pcb = process(processId);
        var queue = queueFor(pcb);
        var original = canonicalResources(pcb);
        if (!(original.residence() instanceof UserImageResidence.Suspended suspended)
                || (pcb.state() != ProcessState.READY_SUSPENDED && pcb.state() != ProcessState.BLOCKED_SUSPENDED)) {
            throw new IllegalStateException("Swap-in requires a suspended process");
        }
        validateQueue(processId, false);
        var swap = suspended.allocation();
        var image = storage.readSwapBlock(swap);
        if (image.size() != pcb.instructionCount()) throw new IllegalStateException("VIRTUAL_MEMORY image length mismatch");
        MemoryAllocation user;
        try {
            user = memory.allocateUser(image.size());
        } catch (MemoryAllocationException shortage) {
            return new SwapResult.Waiting(SwapResult.Reason.INSUFFICIENT_USER_MEMORY);
        }
        ProcessMemoryBounds bounds;
        ProcessResources replacement;
        boolean ready = pcb.state() == ProcessState.READY_SUSPENDED;
        try {
            memory.writeUserBlock(user, image);
            bounds = new ProcessMemoryBounds(user.base(), user.size());
            replacement = original.withResidence(new UserImageResidence.Resident(user));
            validateCommit(pcb, original, false);
            storage.readSwapBlock(swap); // Valida identidad y contenido de origen antes del commit irreversible.
            pcb.relocateSuspended(bounds); // Publica ambos valores sólo tras preparar el contexto; fallo libera USER.
        } catch (RuntimeException | Error failure) {
            cleanup(failure, () -> memory.release(user));
            throw failure;
        }
        // Las precondiciones controladas ya se validaron; estos objetos de sesión no se usan concurrentemente.
        resources.replace(processId, original, replacement);
        storage.releaseSwap(swap);
        pcb.changeState(ready ? ProcessState.READY : ProcessState.BLOCKED);
        if (topology != null) topology.node(pcb.cpuAffinity()).suspendedReadyQueue().remove(processId);
        if (ready) queue.enqueue(processId);
        pcbSynchronizer.sync(pcb);
        return new SwapResult.Completed();
    }

    /** Busca el PCB del PID solicitado o rechaza un proceso desconocido antes de transferir recursos. */
    private ProcessControlBlock process(int pid) {
        return processes.find(pid).orElseThrow(() -> new IllegalArgumentException("Unknown process: " + pid));
    }

    /** Recupera los recursos registrados y valida que Kernel siga conteniendo el PCB canónico. */
    private ProcessResources canonicalResources(ProcessControlBlock pcb) {
        var value = resources.find(pcb.processId()).orElseThrow(() -> new IllegalStateException("Missing process resources"));
        memory.validatePcbAllocation(value.kernel(), pcb);
        return value;
    }

    /** Exige la membresía READY esperada para la transición, sin corregir silenciosamente la cola. */
    private void validateQueue(int pid, boolean expected) {
        var pcb = process(pid);
        if (queueFor(pcb).entries().contains(pid) != expected) throw new IllegalStateException("Inconsistent READY membership");
        if (topology != null && topology.node(pcb.cpuAffinity()).suspendedReadyQueue().entries().contains(pid)
                != (pcb.state() == ProcessState.READY_SUSPENDED)) {
            throw new IllegalStateException("Inconsistent suspended READY membership");
        }
    }

    private ReadyQueue queueFor(ProcessControlBlock pcb) {
        if (topology != null) return topology.node(pcb.cpuAffinity()).readyQueue();
        if (!pcb.cpuAffinity().equals(CpuId.ZERO)) throw new IllegalStateException("Legacy swap service requires CPU 0 affinity");
        return queue;
    }

    /** Revalida identidad de PCB/recursos y membresía READY antes de publicar la transferencia. */
    private void validateCommit(ProcessControlBlock pcb, ProcessResources original, boolean queued) {
        if (process(pcb.processId()) != pcb || canonicalResources(pcb) != original) {
            throw new IllegalStateException("Canonical process resources changed");
        }
        validateQueue(pcb.processId(), queued);
    }

    /** Intenta liberar la reserva destino y adjunta fallos secundarios al error original sin ocultarlo. */
    private static void cleanup(Throwable original, Runnable action) {
        try { action.run(); }
        catch (RuntimeException | Error failure) {
            if (failure != original) original.addSuppressed(failure);
        }
    }
}
