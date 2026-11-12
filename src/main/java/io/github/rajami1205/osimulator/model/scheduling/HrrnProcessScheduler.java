package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.Objects;
import java.util.Optional;

/**
 * Highest Response Ratio Next no expropiativo: entre los READY locales elige el mayor (W + S) / S, con
 * S = Burst declarado y W = tiempo lógico desde el Arrival menos el servicio ya recibido (nunca negativo).
 * W incluye el tiempo bloqueado de un proceso que volvió de un page fault. Los ratios se comparan con
 * productos cruzados en enteros; empates por Arrival Time y luego PID.
 */
public final class HrrnProcessScheduler implements ProcessScheduler {
    private final CpuId cpuId;
    private final ReadyQueue readyQueue;
    private final ProcessTable processes;
    private long now;

    public HrrnProcessScheduler(CpuId cpuId, ReadyQueue readyQueue, ProcessTable processes) {
        this.cpuId = Objects.requireNonNull(cpuId, "cpuId must not be null");
        this.readyQueue = Objects.requireNonNull(readyQueue, "readyQueue must not be null");
        this.processes = Objects.requireNonNull(processes, "processes must not be null");
        readyQueue.requireBinding(cpuId, processes);
    }

    @Override
    public void onSchedulingPoint(long now) {
        this.now = now;
    }

    @Override
    public Optional<Integer> selectNext() {
        return readyQueue.entries().stream().map(this::validated).min(this::compare)
                .map(ProcessControlBlock::processId);
    }

    /** Menor resultado = mejor candidato: mayor ratio, luego menor Arrival y menor PID. */
    private int compare(ProcessControlBlock first, ProcessControlBlock second) {
        // (Wa + Sa) / Sa  vs  (Wb + Sb) / Sb  <=>  (Wa + Sa) * Sb  vs  (Wb + Sb) * Sa
        long firstNumerator = waiting(first) + first.instructionCount();
        long secondNumerator = waiting(second) + second.instructionCount();
        long firstScaled = firstNumerator * second.instructionCount();
        long secondScaled = secondNumerator * first.instructionCount();
        int byRatio = Long.compare(secondScaled, firstScaled);
        if (byRatio != 0) return byRatio;
        int byArrival = Long.compare(first.logicalTimes().arrivalTime(), second.logicalTimes().arrivalTime());
        if (byArrival != 0) return byArrival;
        return Integer.compare(first.processId(), second.processId());
    }

    private long waiting(ProcessControlBlock pcb) {
        long service = pcb.accounting().cpuTicks();
        return Math.max(now - pcb.logicalTimes().arrivalTime() - service, 0);
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
