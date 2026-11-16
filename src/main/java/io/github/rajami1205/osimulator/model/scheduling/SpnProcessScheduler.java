package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/**
 * Shortest Process Next no expropiativo: entre los READY locales elige el menor Burst declarado
 * (instrucciones parseadas); empates por Arrival Time y luego PID. No consume la cola ni hace dispatch.
 */
public final class SpnProcessScheduler implements ProcessScheduler {
    private static final Comparator<ProcessControlBlock> ORDER = Comparator
            .comparingInt(ProcessControlBlock::instructionCount)
            .thenComparingLong(pcb -> pcb.logicalTimes().arrivalTime())
            .thenComparingInt(ProcessControlBlock::processId);

    private final ReadyQueue readyQueue;
    private final ProcessTable processes;
    private final CpuId cpuId;

    public SpnProcessScheduler(CpuId cpuId, ReadyQueue readyQueue, ProcessTable processes) {
        this.cpuId = Objects.requireNonNull(cpuId, "cpuId must not be null");
        this.readyQueue = Objects.requireNonNull(readyQueue, "readyQueue must not be null");
        this.processes = Objects.requireNonNull(processes, "processes must not be null");
        readyQueue.requireBinding(cpuId, processes);
    }

    /** Valida todos los candidatos encolados y reporta inconsistencias en lugar de omitirlas. */
    @Override
    public Optional<Integer> selectNext() {
        return readyQueue.entries().stream().map(this::validated).min(ORDER).map(ProcessControlBlock::processId);
    }

    private ProcessControlBlock validated(int pid) {
        var pcb = processes.find(pid).orElseThrow(
                () -> new IllegalStateException("Queued PID is not resident: " + pid));
        if (pcb.state() != ProcessState.READY || !pcb.cpuAffinity().equals(cpuId)) {
            throw new IllegalStateException("Queued PID is not READY: " + pid);
        }
        return pcb;
    }
}
