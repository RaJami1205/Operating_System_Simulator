package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Lottery Scheduling: cada proceso tiene tickets fijos (no se gastan al ganar) y la CPU se asigna por
 * sorteo, con probabilidad proporcional a los tickets.
 * <p>
 * Se sortea cuando la CPU queda libre y en cada frontera de quantum (sólo cuentan instrucciones
 * completadas). En la frontera participa también el owner: si vuelve a ganar sigue corriendo con quantum
 * nuevo y sin cambio de contexto; si gana otro, el owner es expulsado y se despacha a ese ganador. Con la
 * cola READY vacía no hay sorteo y el owner continúa con quantum nuevo.
 * <p>
 * Los candidatos se ordenan por PID antes de cada sorteo para que la misma semilla produzca siempre el mismo
 * resultado. El generador se inyecta (uno por CPU).
 */
public final class LotteryProcessScheduler implements ProcessScheduler {
    private final CpuId cpuId;
    private final ReadyQueue readyQueue;
    private final ProcessTable processes;
    private final int quantum;
    private final RandomGenerator random;
    private int completedInQuantum;
    private Optional<Integer> pendingWinner = Optional.empty();
    private record Candidate(ProcessControlBlock pcb, int tickets) {}
    private List<Candidate> drawnCandidates = List.of();
    private long boundary;

    @Override
    public void onSchedulingPoint(long now) {
        if (now != boundary && !drawnCandidates.isEmpty()) {
            drawnCandidates = List.of();
            pendingWinner = Optional.empty();
        }
        boundary = now;
    }

    public LotteryProcessScheduler(CpuId cpuId, ReadyQueue readyQueue, ProcessTable processes, int quantum,
            RandomGenerator random) {
        this.cpuId = Objects.requireNonNull(cpuId, "cpuId must not be null");
        this.readyQueue = Objects.requireNonNull(readyQueue, "readyQueue must not be null");
        this.processes = Objects.requireNonNull(processes, "processes must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
        if (quantum < SimulatorConfiguration.MIN_QUANTUM || quantum > SimulatorConfiguration.MAX_QUANTUM) {
            throw new IllegalArgumentException("Quantum must be between "
                    + SimulatorConfiguration.MIN_QUANTUM + " and " + SimulatorConfiguration.MAX_QUANTUM);
        }
        this.quantum = quantum;
        readyQueue.requireBinding(cpuId, processes);
    }

    /**
     * Devuelve el ganador pendiente si sigue en READY; si no, sortea entre los READY. El resultado se
     * conserva hasta el dispatch, así que llamadas repetidas no vuelven a sortear ni consumen la cola.
     */
    @Override
    public Optional<Integer> selectNext() {
        List<Integer> queued = readyQueue.entries();
        if (pendingWinner.isPresent() && queued.contains(pendingWinner.get())) {
            return pendingWinner;
        }
        List<ProcessControlBlock> candidates = new ArrayList<>();
        for (int pid : queued) {
            candidates.add(validated(pid));
        }
        pendingWinner = draw(candidates);
        return pendingWinner;
    }

    /** En la frontera de quantum sortea entre el owner y los READY; expulsa sólo si gana otro. */
    @Override
    public boolean shouldPreempt(ProcessControlBlock owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        if (completedInQuantum < quantum) {
            return false;
        }
        List<Integer> queued = readyQueue.entries();
        if (queued.isEmpty()) {
            drawnCandidates = List.of();
            pendingWinner = Optional.empty();
            completedInQuantum = 0;
            return false;
        }
        List<ProcessControlBlock> candidates = new ArrayList<>();
        candidates.add(owner);
        for (int pid : queued) {
            candidates.add(validated(pid));
        }
        var electorate = candidates.stream().map(pcb -> new Candidate(pcb, pcb.tickets())).toList();
        if (drawnCandidates.equals(electorate) && pendingWinner.isPresent()) return true;
        drawnCandidates = List.of();
        pendingWinner = Optional.empty();
        int winner = draw(candidates).orElseThrow();
        if (winner == owner.processId()) {
            completedInQuantum = 0;
            return false;
        }
        pendingWinner = Optional.of(winner);
        drawnCandidates = electorate;
        return true;
    }

    @Override
    public void onDispatched(int pid) {
        drawnCandidates = List.of();
        completedInQuantum = 0;
        pendingWinner = Optional.empty();
    }

    @Override
    public void onInstructionCompleted(int pid) {
        drawnCandidates = List.of();
        completedInQuantum++;
    }

    /** Sorteo proporcional a los tickets, recorriendo los candidatos ordenados por PID. */
    private Optional<Integer> draw(List<ProcessControlBlock> candidates) {
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        List<ProcessControlBlock> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt(ProcessControlBlock::processId));
        int totalTickets = 0;
        for (ProcessControlBlock pcb : ordered) {
            totalTickets += pcb.tickets();
        }
        int winningTicket = random.nextInt(totalTickets);
        int accumulated = 0;
        for (ProcessControlBlock pcb : ordered) {
            accumulated += pcb.tickets();
            if (winningTicket < accumulated) {
                return Optional.of(pcb.processId());
            }
        }
        throw new IllegalStateException("Winning ticket outside the drawn range");
    }

    private ProcessControlBlock validated(int pid) {
        ProcessControlBlock pcb = processes.find(pid).orElseThrow(
                () -> new IllegalStateException("Queued PID is not resident: " + pid));
        if (pcb.state() != ProcessState.READY || !pcb.cpuAffinity().equals(cpuId)) {
            throw new IllegalStateException("Queued PID is not READY: " + pid);
        }
        return pcb;
    }
}
