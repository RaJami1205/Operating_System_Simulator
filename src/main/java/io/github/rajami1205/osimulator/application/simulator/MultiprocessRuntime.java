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

/** Single-CPU coordination; mechanism services retain their own focused responsibilities. */
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
    // Failed swap-in is retried only after a relevant change, never on each idle Step.
    private boolean reconsiderSwap = true;

    public MultiprocessRuntime(Dispatcher dispatcher, ExecutionEngine engine, ExecutionProgress progress,
            CpuRegisters<Instruction> cpu, MainMemory memory, SimulatedFileSystem filesystem,
            ScreenDevice screen, KeyboardDevice keyboard, KeyboardCompletionService input,
            ProcessCompletionService completion, ProcessSwapService swap, JobScheduler admission,
            JobList jobs, ProcessTable table, ProcessResourceRegistry resources, ReadyQueue ready,
            SuspendedReadyQueue suspended, ProcessScheduler scheduler) {
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

    /** Prepare admission without dispatching or consuming a CPU tick. */
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

    private void admitWaiting() {
        while (true) {
            var result = admission.attemptNextAdmission();
            if (result.isEmpty() || result.orElseThrow() instanceof AdmissionResult.Waiting) return;
        }
    }

    /** Completes queued input without touching the active CPU or consuming ticks. */
    public void drainInput() {
        if (!started) return;
        int before = input.pending().size();
        var terminated = input.drain();
        for (int pid : terminated) completion.complete(pid);
        if (!terminated.isEmpty()) admitWaiting();
        if (input.pending().size() != before) reconsiderSwap = true;
        settle();
    }

    private void settle() {
        if (started && reconsiderSwap && dispatcher.owner().isEmpty()
                && ready.peek().isEmpty() && suspended.peek().isPresent()) {
            int pid = suspended.peek().orElseThrow();
            if (swap.swapIn(pid) instanceof SwapResult.Completed) suspended.remove(pid);
            reconsiderSwap = false;
        }
    }

    /** Dispatch is zero-cost; only this single engine call can consume a tick. */
    public RuntimeStepResult step() {
        if (!started) throw new IllegalStateException("Runtime has not started");
        drainInput();
        validateStructure();
        if (dispatcher.owner().isEmpty()) scheduler.selectNext().ifPresent(dispatcher::dispatch);
        if (dispatcher.owner().isEmpty()) return new RuntimeStepResult.Idle(status());

        var pcb = dispatcher.owner().orElseThrow();
        var result = engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, progress);
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

    /** Called after an explicit resource release, including while the CPU is paused. */
    void retryAdmissionAfterRelease() {
        if (started) admitWaiting();
    }

    public SwapResult swapIn(int pid) {
        rejectOwner(pid);
        var result = swap.swapIn(pid);
        if (result instanceof SwapResult.Completed) {
            suspended.remove(pid);
            reconsiderSwap = true;
        }
        return result;
    }

    private void rejectOwner(int pid) {
        if (dispatcher.owner().filter(p -> p.processId() == pid).isPresent()) {
            throw new IllegalStateException("CPU owner cannot be swapped");
        }
        if (!progress.isIdle() && dispatcher.owner().isEmpty()) {
            throw new IllegalStateException("Active instruction has no CPU owner");
        }
    }

    /** Read-only status after coordination; history does not count as active workload. */
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

    /** Bounded consistency checks validate ownership; they never select a CPU owner. */
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
