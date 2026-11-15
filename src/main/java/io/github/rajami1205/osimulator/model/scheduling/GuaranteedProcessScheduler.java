package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.Objects;
import java.util.Optional;

/**
 * Guaranteed Scheduling preemptivo: promete a cada proceso vivo 1/n de la CPU.
 *
 * <p>ratio = servicio recibido / ((t − Arrival) / n). Un ratio menor que 1 indica que el proceso recibió
 * menos de lo prometido; corre el de menor ratio y se reevalúa en cada tick. En empate con el owner,
 * el owner continúa.
 *
 * <p>n = procesos vivos en esta CPU (READY en la cola local + el owner RUNNING). Como n es el mismo para
 * todos los candidatos de la CPU, se cancela al comparar y no se usa en el cálculo: basta comparar
 * servicio / (t − Arrival) con productos cruzados en enteros.
 *
 * <p>Un proceso sin servicio tiene ratio 0 (máxima urgencia), incluso si acaba de llegar
 * (t − Arrival = 0). El tiempo bloqueado (page fault) cuenta como tiempo transcurrido.
 * Empates: Arrival Time y luego PID.
 */
public final class GuaranteedProcessScheduler implements ProcessScheduler {
    private final CpuId cpuId;
    private final ReadyQueue readyQueue;
    private final ProcessTable processes;
    private long now;

    public GuaranteedProcessScheduler(CpuId cpuId, ReadyQueue readyQueue, ProcessTable processes) {
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
        return readyQueue.entries().stream()
                .map(this::validated)
                .min(this::compareCandidates)
                .map(ProcessControlBlock::processId);
    }

    /** Expulsa sólo si el mejor candidato READY tiene un ratio estrictamente menor que el owner. */
    @Override
    public boolean shouldPreempt(ProcessControlBlock owner) {
        Objects.requireNonNull(owner, "owner must not be null");

        Optional<Integer> bestCandidatePid = selectNext();
        if (bestCandidatePid.isEmpty()) {
            return false;
        }

        ProcessControlBlock bestCandidate = processes.find(bestCandidatePid.get()).orElseThrow(
                () -> new IllegalStateException("Selected candidate not found: " + bestCandidatePid.get()));
        int ratioComparison = compareRatio(bestCandidate, owner);
        return ratioComparison < 0;
    }

    /** Menor resultado = mejor candidato: menor ratio, luego menor Arrival y menor PID. */
    private int compareCandidates(ProcessControlBlock first, ProcessControlBlock second) {
        int byRatio = compareRatio(first, second);
        if (byRatio != 0) {
            return byRatio;
        }
        long firstArrival = first.logicalTimes().arrivalTime();
        long secondArrival = second.logicalTimes().arrivalTime();
        int byArrival = Long.compare(firstArrival, secondArrival);
        if (byArrival != 0) {
            return byArrival;
        }
        return Integer.compare(first.processId(), second.processId());
    }

    /** Compara servicio / transcurrido sin decimales; servicio 0 equivale a ratio 0. */
    private int compareRatio(ProcessControlBlock first, ProcessControlBlock second) {
        long firstService = first.accounting().cpuTicks();
        long secondService = second.accounting().cpuTicks();

        boolean firstUnserved = firstService == 0;
        boolean secondUnserved = secondService == 0;
        if (firstUnserved && secondUnserved) {
            return 0;
        }
        if (firstUnserved) {
            return -1;
        }
        if (secondUnserved) {
            return 1;
        }

        // Sa / Ea  vs  Sb / Eb  <=>  Sa * Eb  vs  Sb * Ea   (Ea, Eb > 0 cuando hay servicio)
        long firstElapsed = elapsed(first);
        long secondElapsed = elapsed(second);
        long firstScaled = firstService * secondElapsed;
        long secondScaled = secondService * firstElapsed;
        return Long.compare(firstScaled, secondScaled);
    }

    private long elapsed(ProcessControlBlock pcb) {
        long arrival = pcb.logicalTimes().arrivalTime();
        return now - arrival;
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
