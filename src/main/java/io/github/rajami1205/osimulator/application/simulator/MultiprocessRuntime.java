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
 * Coordina Step, bloqueo, completion y disponibilidad global sobre un CPU. Delega ownership al Dispatcher
 * y mantiene FCFS non-preemptive; Step ejecuta como máximo un tick.
 */
public final class MultiprocessRuntime {
    private final Dispatcher dispatcher;
    private final ExecutionEngine engine;
    private final ExecutionProgress progress;
    private final CpuRegisters<Instruction> cpu;
    private final MainMemory memory;
    private final SimulatedFileSystem filesystem;
    private final ScreenDevice screen;
    private final KeyboardDevice keyboard;
    private final KeyboardCompletionService input;
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
    private final java.time.Clock realClock;
    // Un swap-in fallido sólo se reintenta tras un cambio relevante, no en cada Step sin trabajo.
    private boolean reconsiderSwap = true;

    /**
     * Recibe los servicios canónicos de sesión y separa el contador de ticks del Clock real; comienza sin
     * workload iniciado.
     */
    public MultiprocessRuntime(Dispatcher dispatcher, ExecutionEngine engine, ExecutionProgress progress,
            CpuRegisters<Instruction> cpu, MainMemory memory, SimulatedFileSystem filesystem,
            ScreenDevice screen, KeyboardDevice keyboard, KeyboardCompletionService input,
            ProcessCompletionService completion, ProcessSwapService swap, JobScheduler admission,
            JobList jobs, ProcessTable table, ProcessResourceRegistry resources, ReadyQueue ready,
            SuspendedReadyQueue suspended, ProcessScheduler scheduler, CpuTickCounter tickCounter, java.time.Clock realClock) {
        this.tickCounter = Objects.requireNonNull(tickCounter);
        this.realClock = Objects.requireNonNull(realClock);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        this.engine = Objects.requireNonNull(engine);
        this.progress = Objects.requireNonNull(progress);
        this.cpu = Objects.requireNonNull(cpu);
        this.memory = Objects.requireNonNull(memory);
        this.filesystem = Objects.requireNonNull(filesystem);
        this.screen = Objects.requireNonNull(screen);
        this.keyboard = Objects.requireNonNull(keyboard);
        this.input = Objects.requireNonNull(input);
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
     * Completa input en contextos guardados y procesa cleanup/admisión derivada; nunca ejecuta el CPU ni
     * suma ticks.
     */
    public void drainInput() {
        if (!started) return;
        int before = input.pending().size();
        var terminated = input.drain();
        for (int pid : terminated) completion.complete(pid);
        if (!terminated.isEmpty()) admitWaiting();
        if (input.pending().size() != before) reconsiderSwap = true;
        settle();
    }

    /**
     * Reintenta swap-in de la cabeza suspended sólo ante cambios relevantes y sin owner/READY; no
     * selecciona víctimas de swap-out.
     */
    private void settle() {
        if (started && reconsiderSwap && dispatcher.owner().isEmpty()
                && ready.peek().isEmpty() && suspended.peek().isPresent()) {
            int pid = suspended.peek().orElseThrow();
            if (swap.swapIn(pid) instanceof SwapResult.Completed) suspended.remove(pid);
            reconsiderSwap = false;
        }
    }

    /**
     * Coordina input y dispatch gratuito, ejecuta como máximo un tick y publica accounting sólo tras
     * éxito; después procesa bloqueo o completion sin preemption por tick.
     */
    public RuntimeStepResult step() {
        if (!started) throw new IllegalStateException("Runtime has not started");
        drainInput();
        validateStructure();
        if (dispatcher.owner().isEmpty()) scheduler.selectNext().ifPresent(dispatcher::dispatch);
        if (dispatcher.owner().isEmpty()) return new RuntimeStepResult.Idle(status());

        var pcb = dispatcher.owner().orElseThrow();
        if (pcb.programCounter() < 0 || pcb.programCounter() >= pcb.instructionCount()) {
            throw new IllegalStateException("Executable owner must have a fetchable logical PC");
        }
        var resource = resources.find(pcb.processId()).orElseThrow(() -> new IllegalStateException("Missing owner resources"));
        if (!(resource.residence() instanceof UserImageResidence.Resident resident)) {
            throw new IllegalStateException("CPU owner must be resident");
        }
        memory.validateUserAllocation(resident.allocation(), pcb.memoryBounds());
        memory.validatePcbAllocation(resource.kernel(), pcb);
        tickCounter.validateCanAdvance();
        var tickStart = realClock.instant();
        // Prepara accounting inmutable antes de ejecutar para detectar overflow antes de aplicar efectos semánticos.
        var accounted = pcb.accounting().recordSuccessfulCpuTick(0, tickStart);
        var result = engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, progress, resident.allocation());
        pcb.replaceAccounting(accounted);
        tickCounter.advance();
        if (result == TickResult.WAITING_FOR_INPUT) {
            dispatcher.release();
            input.register(pcb);
            reconsiderSwap = true;
        } else if (result == TickResult.PROGRAM_FINISHED) {
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

    /** Impide transferir al owner del CPU o continuar con progreso activo sin owner. */
    private void rejectOwner(int pid) {
        if (dispatcher.owner().filter(p -> p.processId() == pid).isPresent()) {
            throw new IllegalStateException("CPU owner cannot be swapped");
        }
        if (!progress.isIdle() && dispatcher.owner().isEmpty()) {
            throw new IllegalStateException("Active instruction has no CPU owner");
        }
    }

    /** Devuelve observación FIFO inmutable sin hacer scheduling ni transferencias. */
    public java.util.List<Integer> suspendedReadyProcessIds() { return suspended.entries(); }

    /** Valida estructura y determina disponibilidad global sin contar historial como workload activo. */
    public RuntimeStatus status() {
        validateStructure();
        if (started && dispatcher.owner().isEmpty() && progress.isIdle() && table.size() == 0
                && resources.entries().isEmpty() && ready.entries().isEmpty() && suspended.entries().isEmpty()
                && input.pending().isEmpty() && jobs.entries().stream().noneMatch(j -> j.state() == JobState.PENDING)) {
            return RuntimeStatus.FINISHED;
        }
        if (dispatcher.owner().isPresent() || ready.peek().isPresent()) return RuntimeStatus.RUNNABLE;
        if (!input.pending().isEmpty()) return RuntimeStatus.WAITING_FOR_INPUT;
        return RuntimeStatus.WAITING_FOR_CAPACITY;
    }

    /**
     * Comprueba correspondencia entre PCBs, estados, recursos, colas, pending input y único owner; no
     * repara ni selecciona procesos.
     */
    private void validateStructure() {
        var pids = new HashSet<Integer>();
        for (var pcb : table.entries()) pids.add(pcb.processId());
        var readyPids = ready.entries();
        var suspendedPids = suspended.entries();
        var owner = dispatcher.owner().orElse(null);
        if (!pids.equals(resources.entries().keySet()) || !pids.containsAll(readyPids)
                || !pids.containsAll(suspendedPids) || (!progress.isIdle() && owner == null)) {
            throw new IllegalStateException("Inconsistent active process structures");
        }
        var pending = new HashSet<Integer>();
        for (var request : input.pending()) {
            if (!pids.contains(request.processId()) || !pending.add(request.processId())) {
                throw new IllegalStateException("Invalid pending request");
            }
        }
        for (var pcb : table.entries()) {
            int pid = pcb.processId();
            var state = pcb.state();
            if ((state == ProcessState.RUNNING) != (owner == pcb)
                    || (state == ProcessState.READY) != readyPids.contains(pid)
                    || (state == ProcessState.READY_SUSPENDED) != suspendedPids.contains(pid)
                    || ((state == ProcessState.BLOCKED || state == ProcessState.BLOCKED_SUSPENDED) != pending.contains(pid))
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
