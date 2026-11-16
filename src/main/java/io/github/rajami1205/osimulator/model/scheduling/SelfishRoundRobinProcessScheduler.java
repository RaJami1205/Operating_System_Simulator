package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.math.BigInteger;

/**
 * Selfish Round Robin: sólo los procesos <em>aceptados</em> compiten por la CPU, en Round Robin con el
 * quantum de la sesión. Los procesos <em>nuevos</em> esperan hasta ser aceptados.
 *
 * <p>Tasas fijas de la política: a = {@value #NEW_RATE} (nuevos), b = {@value #ACCEPTED_RATE} (aceptados).
 * Prioridad de un nuevo = a · (t − Arrival). Un aceptado conserva la prioridad con la que entró y gana
 * b por tick desde ese momento. En cada punto de planificación, un nuevo pasa a aceptado si no hay
 * aceptados READY/RUNNING en esta CPU, o si su prioridad alcanza la del aceptado READY/RUNNING más bajo
 * (los aceptados bloqueados o suspendidos no cuentan). Al aceptarse entra al final de la ronda RR.
 * Los nuevos se evalúan por Arrival y luego PID.
 *
 * <p>La ronda RR es interna: un owner expulsado vuelve al final; un aceptado que regresa a READY tras
 * bloquearse o suspenderse también vuelve al final, sin pasar otra vez por la aceptación. Expulsión sólo
 * al completar el quantum y si hay otro aceptado esperando; si no, el owner sigue con quantum nuevo.
 */
public final class SelfishRoundRobinProcessScheduler implements ProcessScheduler {

    /** Prioridad con la que el proceso fue aceptado y el instante de aceptación. */
    private record AcceptedEntry(BigInteger priorityAtAcceptance, long acceptedAt) { }

    private static final Comparator<ProcessControlBlock> ARRIVAL_ORDER =
            Comparator.<ProcessControlBlock>comparingLong(pcb -> pcb.logicalTimes().arrivalTime())
                      .thenComparingInt(ProcessControlBlock::processId);

    private final CpuId cpuId;
    private final ReadyQueue readyQueue;
    private final ProcessTable processes;
    private final int quantum;
    private final int newRate;
    private final int acceptedRate;

    /** a: prioridad que gana por tick un proceso nuevo. Fija para la política. */
    public static final int NEW_RATE = 2;
    /** b: prioridad que gana por tick un proceso aceptado. Fija para la política (a > b). */
    public static final int ACCEPTED_RATE = 1;

    private final Map<Integer, AcceptedEntry> accepted = new HashMap<>();
    /** Aceptados READY en orden de la ronda RR (el owner no está aquí). */
    private Deque<Integer> acceptedWaiting = new ArrayDeque<>();
    private Integer ownerPid;
    private int completedInQuantum;
    private long now;

    public SelfishRoundRobinProcessScheduler(CpuId cpuId, ReadyQueue readyQueue, ProcessTable processes, int quantum) {
        this(cpuId, readyQueue, processes, quantum, NEW_RATE, ACCEPTED_RATE);
    }

    /** Sólo para tests: permite otras tasas (p. ej. b = 0, que equivale a RR). Exige a > b >= 0. */
    SelfishRoundRobinProcessScheduler(CpuId cpuId, ReadyQueue readyQueue, ProcessTable processes, int quantum,
            int newRate, int acceptedRate) {
        this.cpuId = Objects.requireNonNull(cpuId, "cpuId must not be null");
        this.readyQueue = Objects.requireNonNull(readyQueue, "readyQueue must not be null");
        this.processes = Objects.requireNonNull(processes, "processes must not be null");
        if (quantum < SimulatorConfiguration.MIN_QUANTUM || quantum > SimulatorConfiguration.MAX_QUANTUM) {
            throw new IllegalArgumentException("Quantum must be between "
                    + SimulatorConfiguration.MIN_QUANTUM + " and " + SimulatorConfiguration.MAX_QUANTUM);
        }
        if (acceptedRate < 0 || newRate <= acceptedRate) {
            throw new IllegalArgumentException("Rates must satisfy a > b >= 0");
        }
        this.quantum = quantum;
        this.newRate = newRate;
        this.acceptedRate = acceptedRate;
        readyQueue.requireBinding(cpuId, processes);
    }

    @Override
    public void onSchedulingPoint(long now) {
        this.now = now;
        forgetReleasedOwner();
        forgetTerminatedProcesses();
        dropAcceptedThatLeftReady();
        requeueReturningAccepted();
        acceptNewProcesses();
    }

    @Override
    public Optional<Integer> selectNext() {
        Integer head = acceptedWaiting.peekFirst();
        if (head == null) {
            return Optional.empty();
        }
        validated(head);
        return Optional.of(head);
    }

    @Override
    public boolean shouldPreempt(ProcessControlBlock owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        if (completedInQuantum < quantum) {
            return false;
        }
        if (acceptedWaiting.isEmpty()) {
            completedInQuantum = 0;
            return false;
        }
        // El owner expulsado vuelve al final de la ronda de aceptados.
        return true;
    }

    @Override
    public Runnable preparePreemptionCommit(ProcessControlBlock owner) {
        var rotated = new ArrayDeque<>(acceptedWaiting);
        rotated.addLast(owner.processId());
        return () -> {
            acceptedWaiting = rotated;
            ownerPid = null;
        };
    }

    @Override
    public void onDispatched(int pid) {
        acceptedWaiting.remove(pid);
        ownerPid = pid;
        completedInQuantum = 0;
    }

    @Override
    public void onInstructionCompleted(int pid) {
        completedInQuantum++;
    }

    /** El owner terminó o se bloqueó en el tick anterior: el Dispatcher ya liberó la CPU. */
    private void forgetReleasedOwner() {
        if (ownerPid == null) {
            return;
        }
        Optional<ProcessControlBlock> owner = processes.find(ownerPid);
        boolean stillRunning = owner.isPresent() && owner.get().state() == ProcessState.RUNNING;
        if (!stillRunning) {
            ownerPid = null;
        }
    }

    private void forgetTerminatedProcesses() {
        accepted.keySet().removeIf(pid -> {
            Optional<ProcessControlBlock> pcb = processes.find(pid);
            return pcb.isEmpty() || pcb.get().state() == ProcessState.TERMINATED;
        });
    }

    /** Un aceptado que dejó la ReadyQueue (p. ej. suspendido) sale de la ronda hasta que regrese. */
    private void dropAcceptedThatLeftReady() {
        List<Integer> readyEntries = readyQueue.entries();
        acceptedWaiting.removeIf(pid -> !readyEntries.contains(pid));
    }

    /** Aceptados que volvieron a READY (tras bloqueo o suspensión) entran al final de la ronda. */
    private void requeueReturningAccepted() {
        for (int pid : readyQueue.entries()) {
            boolean isAccepted = accepted.containsKey(pid);
            boolean alreadyInRound = acceptedWaiting.contains(pid);
            if (isAccepted && !alreadyInRound) {
                acceptedWaiting.addLast(pid);
            }
        }
    }

    private void acceptNewProcesses() {
        List<ProcessControlBlock> newProcesses = readyQueue.entries().stream()
                .filter(pid -> !accepted.containsKey(pid))
                .map(this::validated)
                .sorted(ARRIVAL_ORDER)
                .toList();
        for (ProcessControlBlock candidate : newProcesses) {
            BigInteger candidatePriority = newPriority(candidate);
            Optional<BigInteger> lowestAccepted = lowestCompetingAcceptedPriority();
            boolean canJoin = lowestAccepted.isEmpty() || candidatePriority.compareTo(lowestAccepted.orElseThrow()) >= 0;
            if (canJoin) {
                int pid = candidate.processId();
                accepted.put(pid, new AcceptedEntry(candidatePriority, now));
                acceptedWaiting.addLast(pid);
            }
        }
    }

    /** Menor prioridad entre los aceptados READY (en la ronda) y el owner RUNNING. */
    private Optional<BigInteger> lowestCompetingAcceptedPriority() {
        Optional<BigInteger> lowest = acceptedWaiting.stream()
                .map(this::acceptedPriority)
                .min(BigInteger::compareTo);
        if (ownerPid == null || !accepted.containsKey(ownerPid)) {
            return lowest;
        }
        BigInteger ownerPriority = acceptedPriority(ownerPid);
        if (lowest.isEmpty() || ownerPriority.compareTo(lowest.orElseThrow()) < 0) {
            return Optional.of(ownerPriority);
        }
        return lowest;
    }

    private BigInteger newPriority(ProcessControlBlock pcb) {
        long waited = now - pcb.logicalTimes().arrivalTime();
        return BigInteger.valueOf(newRate).multiply(BigInteger.valueOf(Math.max(waited, 0)));
    }

    private BigInteger acceptedPriority(int pid) {
        AcceptedEntry entry = accepted.get(pid);
        long sinceAcceptance = now - entry.acceptedAt();
        BigInteger increase = BigInteger.valueOf(acceptedRate).multiply(BigInteger.valueOf(sinceAcceptance));
        return entry.priorityAtAcceptance().add(increase);
    }

    private ProcessControlBlock validated(int pid) {
        ProcessControlBlock pcb = processes.find(pid).orElseThrow(
                () -> new IllegalStateException("Queued PID is not registered: " + pid));
        if (pcb.state() != ProcessState.READY || !pcb.cpuAffinity().equals(cpuId)) {
            throw new IllegalStateException("Queued PID is not READY on this CPU: " + pid);
        }
        return pcb;
    }
}
