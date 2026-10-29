package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.job.JobScheduler;
import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.execution.*;
import io.github.rajami1205.osimulator.model.filesystem.SimulatedFileSystem;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.io.*;
import io.github.rajami1205.osimulator.model.job.*;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.scheduling.*;
import java.util.HashSet;
import java.util.Objects;

/**
 * Coordina Step, completion y disponibilidad global sobre un CPU. Delega ownership al Dispatcher
 * y mantiene FCFS non-preemptive; Step ejecuta como máximo un tick.
 */
public final class MultiprocessRuntime {
    private final Dispatcher dispatcher;
    private final ExecutionEngine engine;
    private final CpuRegisters<Instruction> cpu;
    private final PcbKernelSynchronizer pcbSynchronizer;
    private final MainMemory memory;
    private final SimulatedFileSystem filesystem;
    private final ScreenDevice screen;
    private final ProcessCompletionService completion;
    private final ProcessSwapService swap;
    private final JobScheduler admission;
    private final JobList jobs;
    private final ProcessTable table;
    private final ProcessResourceRegistry resources;
    private final ReadyQueue ready;
    private final SuspendedReadyQueue suspended;
    private final ProcessScheduler scheduler;
    private boolean started;
    private final CpuTickCounter tickCounter;
    private final SimulationClock simulationClock;
    private final java.time.Clock realClock;
    // Un swap-in fallido sólo se reintenta tras un cambio relevante, no en cada Step sin trabajo.
    private boolean reconsiderSwap = true;

    /**
     * Recibe los servicios canónicos de sesión y separa el contador de ticks del Clock real; comienza sin
     * workload iniciado.
     */
    public MultiprocessRuntime(Dispatcher dispatcher, ExecutionEngine engine,
            CpuRegisters<Instruction> cpu, MainMemory memory, SimulatedFileSystem filesystem,
            ScreenDevice screen,
            ProcessCompletionService completion, ProcessSwapService swap, JobScheduler admission,
            JobList jobs, ProcessTable table, ProcessResourceRegistry resources, ReadyQueue ready,
            SuspendedReadyQueue suspended, ProcessScheduler scheduler, CpuTickCounter tickCounter,
            SimulationClock simulationClock, java.time.Clock realClock) {
        this.tickCounter = Objects.requireNonNull(tickCounter);
        this.simulationClock = Objects.requireNonNull(simulationClock);
        this.realClock = Objects.requireNonNull(realClock);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.engine = Objects.requireNonNull(engine);
        this.cpu = Objects.requireNonNull(cpu);
        this.memory = Objects.requireNonNull(memory);
        this.pcbSynchronizer = new PcbKernelSynchronizer(memory, resources);
        this.filesystem = Objects.requireNonNull(filesystem);
        this.screen = Objects.requireNonNull(screen);
        this.completion = Objects.requireNonNull(completion);
        this.swap = Objects.requireNonNull(swap);
        this.admission = Objects.requireNonNull(admission);
        this.jobs = Objects.requireNonNull(jobs);
        this.table = Objects.requireNonNull(table);
        this.resources = Objects.requireNonNull(resources);
        this.ready = Objects.requireNonNull(ready);
        this.suspended = Objects.requireNonNull(suspended);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    /** Prepara admisión de un workload no vacío una sola vez, sin dispatch ni consumo de ticks. */
    public void start() {
        if (started || jobs.entries().isEmpty()) {
            throw new IllegalStateException("Nonempty unstarted workload required");
        }
        validateStructure();
        admitWaiting();
        started = true;
        settle();
        validateStructure();
    }

    /** Admite en orden hasta agotar PENDING o encontrar el primer Job que debe esperar. */
    private void admitWaiting() {
        while (true) {
            var result = admission.attemptNextAdmission();
            if (result.isEmpty() || result.orElseThrow() instanceof AdmissionResult.Waiting) return;
        }
    }

    /**
     * Reintenta swap-in de la cabeza suspended sólo ante cambios relevantes y sin owner/READY; no
     * selecciona víctimas de swap-out.
     */
    void settle() {
        if (started && reconsiderSwap && dispatcher.owner().isEmpty()
                && ready.peek().isEmpty() && suspended.peek().isPresent()) {
            int pid = suspended.peek().orElseThrow();
            if (swap.swapIn(pid) instanceof SwapResult.Completed) suspended.remove(pid);
            reconsiderSwap = false;
        }
    }

    /**
     * Reconsidera swap-in gratuito; confirma un segundo por tick exitoso o espera válida. Un tick se confirma
     * tras sincronizar CPU/PCB/Kernel, antes del cleanup posterior; FINISHED sin CPU no consume tiempo.
     */
    public RuntimeStepResult step() {
        if (!started) throw new IllegalStateException("Runtime has not started");
        settle();
        // Reconsiderar swap-in no consume tiempo; FINISHED no abre un intervalo.
        if (status() == RuntimeStatus.FINISHED) return new RuntimeStepResult.Idle(RuntimeStatus.FINISHED);
        simulationClock.validateCanAdvance();
        if (dispatcher.owner().isEmpty()) scheduler.selectNext().ifPresent(dispatcher::dispatch);
        if (dispatcher.owner().isEmpty()) {
            var idleStatus = status();
            if (idleStatus == RuntimeStatus.WAITING_FOR_CAPACITY) {
                simulationClock.advance();
            }
            return new RuntimeStepResult.Idle(idleStatus);
        }

        var pcb = dispatcher.owner().orElseThrow();
        if (!pcb.hasExecutableProgramCounter()) {
            throw new IllegalStateException("Executable owner must have a fetchable logical PC");
        }
        var resource = resources.find(pcb.processId()).orElseThrow(() -> new IllegalStateException("Missing owner resources"));
        if (!(resource.residence() instanceof UserImageResidence.Resident resident)) {
            throw new IllegalStateException("CPU owner must be resident");
        }
        memory.validateUserAllocation(resident.allocation(), pcb.memoryBounds());
        memory.validatePcbAllocation(resource.kernel(), pcb);
        if (!cpu.snapshot().equals(pcb.cpuContext())) {
            throw new IllegalStateException("CPU context differs from canonical PCB");
        }
        tickCounter.validateCanAdvance();
        var tickStart = realClock.instant();
        // Prepara accounting inmutable antes de ejecutar para detectar overflow antes de aplicar efectos semánticos.
        var accounted = pcb.accounting().recordSuccessfulCpuTick(0, tickStart);
        var result = engine.executeTick(filesystem, screen, memory, cpu, pcb, resident.allocation());
        pcb.replaceCpuContext(cpu.snapshot());
        pcb.replaceAccounting(accounted);
        pcbSynchronizer.sync(pcb);
        tickCounter.advance();
        // Commit temporal global: el cleanup posterior puede fallar sin deshacer este intervalo.
        simulationClock.advance();
        if (result == TickResult.PROGRAM_FINISHED) {
            dispatcher.release();
            completion.complete(pcb.processId());
            admitWaiting();
            reconsiderSwap = true;
        }
        settle();
        return new RuntimeStepResult.Executed(pcb.processId(), result, status());
    }

    /**
     * Rechaza el owner y coordina salida explícita; añade a la cola suspended únicamente procesos que
     * quedan READY_SUSPENDED.
     */
    public SwapResult swapOut(int pid) {
        rejectOwner(pid);
        if (suspended.entries().contains(pid)) throw new IllegalStateException("Already queued suspended process");
        var result = swap.swapOut(pid);
        if (result instanceof SwapResult.Completed) {
            if (table.find(pid).orElseThrow().state() == ProcessState.READY_SUSPENDED) suspended.enqueue(pid);
            reconsiderSwap = true;
        }
        return result;
    }

    /** Reintenta Jobs pendientes tras liberación explícita si el workload inició, incluso durante Pause. */
    void retryAdmissionAfterRelease() {
        if (started) admitWaiting();
    }

    /**
     * Coordina entrada explícita, retira membresía suspended al completarla y habilita reconsideración de
     * disponibilidad.
     */
    public SwapResult swapIn(int pid) {
        rejectOwner(pid);
        var result = swap.swapIn(pid);
        if (result instanceof SwapResult.Completed) {
            suspended.remove(pid);
            reconsiderSwap = true;
        }
        return result;
    }

    /** Impide transferir al owner del CPU. */
    private void rejectOwner(int pid) {
        if (dispatcher.owner().filter(p -> p.processId() == pid).isPresent()) {
            throw new IllegalStateException("CPU owner cannot be swapped");
        }
    }

    /** Devuelve observación FIFO inmutable sin hacer scheduling ni transferencias. */
    public java.util.List<Integer> suspendedReadyProcessIds() { return suspended.entries(); }

    /** Valida estructura y determina disponibilidad global sin contar historial como workload activo. */
    public RuntimeStatus status() {
        validateStructure();
        if (started && dispatcher.owner().isEmpty() && table.size() == 0
                && resources.entries().isEmpty() && ready.entries().isEmpty() && suspended.entries().isEmpty()
                && jobs.entries().stream().noneMatch(j -> j.state() == JobState.PENDING)) {
            return RuntimeStatus.FINISHED;
        }
        if (dispatcher.owner().isPresent() || ready.peek().isPresent()) return RuntimeStatus.RUNNABLE;
        return RuntimeStatus.WAITING_FOR_CAPACITY;
    }

    /**
     * Comprueba correspondencia entre PCBs, estados, recursos, colas y único owner; no
     * repara ni selecciona procesos.
     */
    private void validateStructure() {
        var pids = new HashSet<Integer>();
        for (var pcb : table.entries()) pids.add(pcb.processId());
        var readyPids = ready.entries();
        var suspendedPids = suspended.entries();
        var owner = dispatcher.owner().orElse(null);
        if (!pids.equals(resources.entries().keySet()) || !pids.containsAll(readyPids)
                || !pids.containsAll(suspendedPids)) {
            throw new IllegalStateException("Inconsistent active process structures");
        }
        for (var pcb : table.entries()) {
            int pid = pcb.processId();
            var state = pcb.state();
            if (state == ProcessState.BLOCKED || state == ProcessState.BLOCKED_SUSPENDED) {
                throw new IllegalStateException("Blocked process has no supported unblocking mechanism");
            }
            if ((state == ProcessState.RUNNING) != (owner == pcb)
                    || (state == ProcessState.READY) != readyPids.contains(pid)
                    || (state == ProcessState.READY_SUSPENDED) != suspendedPids.contains(pid)
                    || state == ProcessState.NEW || state == ProcessState.TERMINATED) {
                throw new IllegalStateException("Inconsistent process state/queue/owner");
            }
            boolean swapped = state == ProcessState.READY_SUSPENDED || state == ProcessState.BLOCKED_SUSPENDED;
            if (swapped != (resources.find(pid).orElseThrow().residence() instanceof UserImageResidence.Suspended)) {
                throw new IllegalStateException("State and image residence disagree");
            }
        }
        if (owner != null && table.find(owner.processId()).orElse(null) != owner) {
            throw new IllegalStateException("Noncanonical CPU owner");
        }
    }
}
