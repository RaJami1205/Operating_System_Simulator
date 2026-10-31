package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.cpu.CpuTopology;
import io.github.rajami1205.osimulator.application.job.JobScheduler;
import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.execution.*;
import io.github.rajami1205.osimulator.model.filesystem.SimulatedFileSystem;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.io.ScreenDevice;
import io.github.rajami1205.osimulator.model.job.*;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.memory.MemoryAllocation;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.scheduling.*;
import java.util.*;

/** Un intervalo global determinista con FCFS, registros, ticks y ownership locales. */
public final class MultiprocessRuntime {
    // Referencias a recursos existentes: el adaptador legacy no crea otra CPU ni otras colas.
    private record LocalCpu(CpuId id, Dispatcher dispatcher, CpuRegisters<Instruction> registers,
            ReadyQueue ready, SuspendedReadyQueue suspended, ProcessScheduler scheduler, CpuTickCounter ticks) {
        LocalCpu {
            Objects.requireNonNull(id); Objects.requireNonNull(dispatcher); Objects.requireNonNull(registers);
            Objects.requireNonNull(ready); Objects.requireNonNull(suspended);
            Objects.requireNonNull(scheduler); Objects.requireNonNull(ticks);
        }
    }
    private record Participant(LocalCpu node, ProcessControlBlock pcb, MemoryAllocation allocation,
            ProcessAccounting accounting) { }
    private final List<LocalCpu> cpus;
    private final ExecutionEngine engine;
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
    private final SimulationClock simulationClock;
    private final java.time.Clock realClock;
    private boolean started;
    private final Set<CpuId> reconsiderSwap = new HashSet<>();
    private boolean reconsiderAdmission = true;
    private long lastAdmissionTime = -1;
    private List<RuntimeStepResult.CpuResult> lastCpuResults = List.of();

    public MultiprocessRuntime(CpuTopology topology, ExecutionEngine engine, MainMemory memory,
            SimulatedFileSystem filesystem, ScreenDevice screen, ProcessCompletionService completion,
            ProcessSwapService swap, JobScheduler admission, JobList jobs, ProcessTable table,
            ProcessResourceRegistry resources, SimulationClock simulationClock, java.time.Clock realClock) {
        this(Objects.requireNonNull(topology).nodes().stream().map(node -> new LocalCpu(node.id(),
                node.dispatcher(), node.registers(), node.readyQueue(), node.suspendedReadyQueue(),
                node.scheduler(), node.tickCounter())).toList(), engine, memory, filesystem, screen,
                completion, swap, admission, jobs, table, resources, simulationClock, realClock);
        topology.requireSharedResources(table, memory, resources);
    }

    /** Compatibilidad CPU0 usando exactamente los recursos suministrados. */
    public MultiprocessRuntime(Dispatcher dispatcher, ExecutionEngine engine,
            CpuRegisters<Instruction> cpu, MainMemory memory, SimulatedFileSystem filesystem,
            ScreenDevice screen, ProcessCompletionService completion, ProcessSwapService swap,
            JobScheduler admission, JobList jobs, ProcessTable table, ProcessResourceRegistry resources,
            ReadyQueue ready, SuspendedReadyQueue suspended, ProcessScheduler scheduler,
            CpuTickCounter tickCounter, SimulationClock simulationClock, java.time.Clock realClock) {
        this(List.of(new LocalCpu(CpuId.ZERO, dispatcher, cpu, ready, suspended, scheduler, tickCounter)),
                engine, memory, filesystem, screen, completion, swap, admission, jobs, table, resources,
                simulationClock, realClock);
    }

    private MultiprocessRuntime(List<LocalCpu> cpus, ExecutionEngine engine, MainMemory memory,
            SimulatedFileSystem filesystem, ScreenDevice screen, ProcessCompletionService completion,
            ProcessSwapService swap, JobScheduler admission, JobList jobs, ProcessTable table,
            ProcessResourceRegistry resources, SimulationClock simulationClock, java.time.Clock realClock) {
        this.cpus = List.copyOf(cpus);
        this.engine = Objects.requireNonNull(engine);
        this.memory = Objects.requireNonNull(memory);
        this.resources = Objects.requireNonNull(resources);
        pcbSynchronizer = new PcbKernelSynchronizer(memory, resources);
        this.filesystem = Objects.requireNonNull(filesystem);
        this.screen = Objects.requireNonNull(screen);
        this.completion = Objects.requireNonNull(completion);
        this.swap = Objects.requireNonNull(swap);
        this.admission = Objects.requireNonNull(admission);
        this.jobs = Objects.requireNonNull(jobs);
        this.table = Objects.requireNonNull(table);
        this.simulationClock = Objects.requireNonNull(simulationClock);
        this.realClock = Objects.requireNonNull(realClock);
        reconsiderAllSwaps();
    }

    public void start() {
        if (started || jobs.entries().isEmpty()) throw new IllegalStateException("Nonempty unstarted workload required");
        validateStructure();
        admitWaiting();
        started = true;
        settle();
        validateStructure();
    }

    private void admitWaiting() {
        long now = simulationClock.current();
        if (!reconsiderAdmission && jobs.entries().stream().noneMatch(job -> job.state() == JobState.PENDING
                && job.arrivalTime() > lastAdmissionTime && job.arrivalTime() <= now)) return;
        reconsiderAdmission = false;
        lastAdmissionTime = now;
        while (true) {
            var result = admission.attemptNextAdmission(now);
            if (result.isEmpty() || result.orElseThrow() instanceof AdmissionResult.Waiting) return;
        }
    }

    /** Cada cabeza local se reintenta una vez por cambio relevante; no selecciona víctimas. */
    void settle() {
        if (!started) return;
        for (var node : cpus) {
            if (reconsiderSwap.contains(node.id) && node.dispatcher.owner().isEmpty()
                    && node.ready.peek().isEmpty() && node.suspended.peek().isPresent()) {
                int pid = node.suspended.peek().orElseThrow();
                if (swap.swapIn(pid) instanceof SwapResult.Completed) node.suspended.remove(pid);
                reconsiderSwap.remove(node.id);
            }
        }
    }

    /** Último intervalo, inspeccionable también después de ERROR. */
    public List<RuntimeStepResult.CpuResult> lastCpuResults() { return lastCpuResults; }

    public RuntimeStepResult step() {
        if (!started) throw new IllegalStateException("Runtime has not started");
        lastCpuResults = List.of();
        if (status() == RuntimeStatus.FINISHED) return idleResult(RuntimeStatus.FINISHED);
        simulationClock.validateCanAdvance();
        long finishTime = Math.incrementExact(simulationClock.current());
        admitWaiting();
        settle();
        for (var node : cpus) {
            if (node.dispatcher.owner().isEmpty()) node.scheduler.selectNext().ifPresent(
                    pid -> node.dispatcher.dispatch(pid, simulationClock.current()));
        }
        var participants = new ArrayList<Participant>();
        var outcomes = new ArrayList<RuntimeStepResult.CpuResult>();
        for (var node : cpus) {
            outcomes.add(RuntimeStepResult.CpuResult.unexecuted(node.id,
                    node.dispatcher.owner().map(ProcessControlBlock::processId)));
        }
        lastCpuResults = List.copyOf(outcomes);
        // Congelar y prevalidar TODAS las CPU antes de aplicar cualquier instrucción.
        for (var node : cpus) {
            if (node.dispatcher.owner().isPresent()) participants.add(prepare(node));
        }
        if (participants.isEmpty()) {
            simulationClock.advance();
            admitWaiting();
            settle();
            return idleResult(status());
        }

        var finished = new ArrayList<Participant>();
        RuntimeException failure = null;
        boolean committed = false;
        for (var participant : participants) {
            var node = participant.node;
            var pcb = participant.pcb;
            try {
                var result = engine.executeTick(filesystem, screen, memory, node.registers, pcb, participant.allocation);
                if (result == TickResult.PROGRAM_FINISHED) pcb.logicalTimes().finishedAt(finishTime);
                pcb.replaceCpuContext(node.registers.snapshot());
                pcb.replaceAccounting(participant.accounting);
                pcbSynchronizer.sync(pcb);
                node.ticks.advance();
                committed = true;
                outcomes.set(node.id.value(), RuntimeStepResult.CpuResult.executed(node.id, pcb.processId(), result));
                if (result == TickResult.PROGRAM_FINISHED) {
                    pcb.recordLogicalFinish(finishTime);
                    finished.add(participant);
                }
            } catch (RuntimeException error) {
                outcomes.set(node.id.value(), new RuntimeStepResult.CpuResult(node.id,
                        Optional.of(pcb.processId()), RuntimeStepResult.CpuOutcome.FAILED, Optional.empty()));
                failure = error;
                break;
            }
        }
        lastCpuResults = List.copyOf(outcomes);
        // Único commit global, también si una CPU posterior falla; overflow ya prevalidado.
        if (committed) simulationClock.advance();
        // Cleanup posterior: nunca cambia participantes ni oculta la excepción original.
        for (var participant : finished) {
            try {
                participant.node.dispatcher.release();
                completion.complete(participant.pcb.processId());
                reconsiderAdmission = true;
                reconsiderAllSwaps();
            } catch (RuntimeException error) {
                if (failure == null) failure = error;
                else if (failure != error) failure.addSuppressed(error);
                break;
            }
        }
        if (failure != null) throw failure;
        admitWaiting();
        settle();
        var status = status();
        if (cpus.size() == 1) {
            var first = outcomes.getFirst();
            return new RuntimeStepResult.Executed(first.processId().orElseThrow(), first.tickResult().orElseThrow(), status);
        }
        return new RuntimeStepResult.Global(outcomes, status);
    }

    private Participant prepare(LocalCpu node) {
        var pcb = node.dispatcher.owner().orElseThrow();
        if (!pcb.cpuAffinity().equals(node.id) || !pcb.hasExecutableProgramCounter()) {
            throw new IllegalStateException("Invalid CPU affinity or executable PC");
        }
        var resource = resources.find(pcb.processId()).orElseThrow(() -> new IllegalStateException("Missing owner resources"));
        if (!(resource.residence() instanceof UserImageResidence.Resident resident)) {
            throw new IllegalStateException("CPU owner must be resident");
        }
        memory.validateUserAllocation(resident.allocation(), pcb.memoryBounds());
        memory.validatePcbAllocation(resource.kernel(), pcb);
        if (!node.registers.snapshot().equals(pcb.cpuContext())) throw new IllegalStateException("CPU context differs from canonical PCB");
        node.ticks.validateCanAdvance();
        return new Participant(node, pcb, resident.allocation(),
                pcb.accounting().recordSuccessfulCpuTick(node.id.value(), realClock.instant()));
    }

    private RuntimeStepResult idleResult(RuntimeStatus status) {
        lastCpuResults = cpus.stream().map(node -> RuntimeStepResult.CpuResult.unexecuted(node.id, Optional.empty())).toList();
        return cpus.size() == 1 ? new RuntimeStepResult.Idle(status) : new RuntimeStepResult.Global(lastCpuResults, status);
    }

    public SwapResult swapOut(int pid) {
        rejectOwner(pid);
        var suspended = nodeFor(table.find(pid).orElseThrow()).suspended;
        if (suspended.entries().contains(pid)) throw new IllegalStateException("Already queued suspended process");
        var result = swap.swapOut(pid);
        if (result instanceof SwapResult.Completed) {
            if (table.find(pid).orElseThrow().state() == ProcessState.READY_SUSPENDED
                    && !suspended.entries().contains(pid)) suspended.enqueue(pid);
            reconsiderAllSwaps();
        }
        return result;
    }

    void retryAdmissionAfterRelease() {
        if (started) { reconsiderAdmission = true; admitWaiting(); }
    }

    public SwapResult swapIn(int pid) {
        rejectOwner(pid);
        var result = swap.swapIn(pid);
        if (result instanceof SwapResult.Completed) {
            nodeFor(table.find(pid).orElseThrow()).suspended.remove(pid);
            reconsiderAllSwaps();
        }
        return result;
    }

    private void reconsiderAllSwaps() { cpus.forEach(node -> reconsiderSwap.add(node.id)); }

    private void rejectOwner(int pid) {
        if (cpus.stream().anyMatch(node -> node.dispatcher.owner().filter(p -> p.processId() == pid).isPresent())) {
            throw new IllegalStateException("CPU owner cannot be swapped");
        }
    }

    /** Vista legacy explícita CPU0. */
    public List<Integer> suspendedReadyProcessIds() { return cpus.getFirst().suspended.entries(); }

    public RuntimeStatus status() {
        validateStructure();
        if (started && table.size() == 0 && resources.entries().isEmpty()
                && jobs.entries().stream().noneMatch(j -> j.state() == JobState.PENDING)) return RuntimeStatus.FINISHED;
        if (cpus.stream().anyMatch(node -> node.dispatcher.owner().isPresent() || node.ready.peek().isPresent())) {
            return RuntimeStatus.RUNNABLE;
        }
        if (table.size() == 0 && jobs.entries().stream().noneMatch(job -> job.state() == JobState.PENDING
                && job.arrivalTime() <= simulationClock.current()) && nextArrivalTime().isPresent()) {
            return RuntimeStatus.WAITING_FOR_ARRIVAL;
        }
        return RuntimeStatus.WAITING_FOR_CAPACITY;
    }

    public OptionalLong nextArrivalTime() {
        return jobs.entries().stream().filter(job -> job.state() == JobState.PENDING
                && job.arrivalTime() > simulationClock.current()).mapToLong(Job::arrivalTime).min();
    }

    private LocalCpu nodeFor(ProcessControlBlock pcb) {
        int id = pcb.cpuAffinity().value();
        if (id >= cpus.size()) throw new IllegalStateException("Affinity outside session topology");
        return cpus.get(id);
    }

    private void validateStructure() {
        var pids = new HashSet<Integer>();
        table.entries().forEach(pcb -> pids.add(pcb.processId()));
        if (!pids.equals(resources.entries().keySet())) throw new IllegalStateException("Inconsistent active process structures");
        var assigned = new HashSet<Integer>();
        for (var node : cpus) {
            var owner = node.dispatcher.owner().orElse(null);
            if (owner != null) {
                if (table.find(owner.processId()).orElse(null) != owner || !owner.cpuAffinity().equals(node.id)
                        || owner.state() != ProcessState.RUNNING || !assigned.add(owner.processId())
                        || !node.registers.snapshot().equals(owner.cpuContext())) {
                    throw new IllegalStateException("Noncanonical CPU owner or context");
                }
            }
            for (int pid : node.ready.entries()) validateQueued(node, pid, ProcessState.READY, assigned);
            for (int pid : node.suspended.entries()) validateQueued(node, pid, ProcessState.READY_SUSPENDED, assigned);
        }
        for (var pcb : table.entries()) {
            var node = nodeFor(pcb);
            var state = pcb.state();
            if (state == ProcessState.BLOCKED || state == ProcessState.BLOCKED_SUSPENDED) {
                throw new IllegalStateException("Blocked process has no supported unblocking mechanism");
            }
            int pid = pcb.processId();
            if ((state == ProcessState.RUNNING) != (node.dispatcher.owner().orElse(null) == pcb)
                    || (state == ProcessState.READY) != node.ready.entries().contains(pid)
                    || (state == ProcessState.READY_SUSPENDED) != node.suspended.entries().contains(pid)
                    || state == ProcessState.NEW || state == ProcessState.TERMINATED) {
                throw new IllegalStateException("Inconsistent process state/queue/owner");
            }
            if ((state == ProcessState.READY_SUSPENDED)
                    != (resources.find(pid).orElseThrow().residence() instanceof UserImageResidence.Suspended)) {
                throw new IllegalStateException("State and image residence disagree");
            }
        }
    }

    private void validateQueued(LocalCpu node, int pid, ProcessState state, Set<Integer> assigned) {
        var pcb = table.find(pid).orElseThrow(() -> new IllegalStateException("Unknown queued process"));
        if (!pcb.cpuAffinity().equals(node.id) || pcb.state() != state || !assigned.add(pid)) {
            throw new IllegalStateException("Invalid local queue membership");
        }
    }
}
