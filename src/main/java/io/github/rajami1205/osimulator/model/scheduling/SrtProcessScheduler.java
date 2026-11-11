package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * Shortest Remaining Time preemptivo: en cada tick elige al proceso READY con menor tiempo restante
 * (Burst − instrucciones completadas). En empate con el owner actual, no hay expulsión.
 */
public final class SrtProcessScheduler implements ProcessScheduler {

    private static final Comparator<ProcessControlBlock> SELECTION_ORDER =
            Comparator.comparingInt(SrtProcessScheduler::remaining)
                      .thenComparingLong(pcb -> pcb.logicalTimes().arrivalTime())
                      .thenComparingInt(ProcessControlBlock::processId);

    private final CpuId cpuId;
    private final ReadyQueue readyQueue;
    private final ProcessTable processes;

    public SrtProcessScheduler(CpuId cpuId, ReadyQueue readyQueue, ProcessTable processes) {
        this.cpuId = Objects.requireNonNull(cpuId, "cpuId must not be null");
        this.readyQueue = Objects.requireNonNull(readyQueue, "readyQueue must not be null");
        this.processes = Objects.requireNonNull(processes, "processes must not be null");
        readyQueue.requireBinding(cpuId, processes);
    }

    @Override
    public Optional<Integer> selectNext() {
        return readyQueue.entries().stream()
                .map(this::validated)
                .min(SELECTION_ORDER)
                .map(ProcessControlBlock::processId);
    }

    /**
     * Retorna true sólo si existe un candidato READY con tiempo restante estrictamente menor al del
     * owner. En empate, el owner continúa ejecutando.
     */
    @Override
    public boolean shouldPreempt(ProcessControlBlock owner) {
        Objects.requireNonNull(owner, "owner must not be null");

        if (readyQueue.entries().isEmpty()) {
            return false;
        }

        int ownerRemaining = remaining(owner);

        Optional<Integer> bestCandidatePid = selectNext();
        if (bestCandidatePid.isEmpty()) {
            return false;
        }

        ProcessControlBlock bestCandidate = processes.find(bestCandidatePid.get()).orElseThrow(
                () -> new IllegalStateException("Selected candidate not found: " + bestCandidatePid.get()));
        int candidateRemaining = remaining(bestCandidate);

        return candidateRemaining < ownerRemaining;
    }

    private static int remaining(ProcessControlBlock pcb) {
        int burst = pcb.instructionCount();
        int service = (int) pcb.accounting().cpuTicks();
        return Math.max(burst - service, 0);
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
