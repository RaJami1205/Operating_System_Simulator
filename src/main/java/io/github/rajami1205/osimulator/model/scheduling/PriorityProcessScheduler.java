package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.configuration.PriorityConfiguration;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Planificación por prioridad: menor número = mayor prioridad (0..9, base guardada en el PCB).
 * <p>
 * Modo no apropiativo: el owner corre hasta terminar o bloquearse. Modo apropiativo: al inicio del tick se
 * expulsa al owner sólo si un READY tiene prioridad efectiva estrictamente mejor que la base del owner.
 * <p>
 * Aging (N &gt; 0): la prioridad efectiva de un proceso READY mejora en 1 cada N ticks que lleva esperando en
 * la cola, con piso 0. La prioridad efectiva vive sólo aquí (el PCB conserva la base) y la espera se
 * reinicia cada vez que el proceso es despachado. La espera cuenta desde que el proceso entra a la cola
 * READY de esta CPU (admisión o regreso), no desde su Arrival si la admisión se retrasó.
 * <p>
 * Empates: menor Arrival Time y luego menor PID.
 */
public final class PriorityProcessScheduler implements ProcessScheduler {
    private final CpuId cpuId;
    private final ReadyQueue readyQueue;
    private final ProcessTable processes;
    private final PriorityConfiguration configuration;
    private final Map<Integer, Long> readySince = new HashMap<>();
    private long now;

    public PriorityProcessScheduler(CpuId cpuId, ReadyQueue readyQueue, ProcessTable processes,
            PriorityConfiguration configuration) {
        this.cpuId = Objects.requireNonNull(cpuId, "cpuId must not be null");
        this.readyQueue = Objects.requireNonNull(readyQueue, "readyQueue must not be null");
        this.processes = Objects.requireNonNull(processes, "processes must not be null");
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        readyQueue.requireBinding(cpuId, processes);
    }

    /** Registra el tiempo vigente y el instante de entrada a READY de los procesos encolados. */
    @Override
    public void onSchedulingPoint(long now) {
        this.now = now;
        List<Integer> queued = readyQueue.entries();
        readySince.keySet().retainAll(queued);
        for (int pid : queued) {
            readySince.putIfAbsent(pid, now);
        }
    }

    @Override
    public Optional<Integer> selectNext() {
        return readyQueue.entries().stream()
                .map(this::validated)
                .min(this::compare)
                .map(ProcessControlBlock::processId);
    }

    /** Sólo en modo apropiativo y sólo ante una prioridad efectiva estrictamente mejor que la del owner. */
    @Override
    public boolean shouldPreempt(ProcessControlBlock owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        if (!configuration.preemptive()) {
            return false;
        }
        Optional<Integer> bestCandidatePid = selectNext();
        if (bestCandidatePid.isEmpty()) {
            return false;
        }
        ProcessControlBlock bestCandidate = validated(bestCandidatePid.get());
        int candidatePriority = effectivePriority(bestCandidate);
        int ownerPriority = owner.priority();
        return candidatePriority < ownerPriority;
    }

    /** Al obtener la CPU, el proceso pierde el aging acumulado. */
    @Override
    public void onDispatched(int pid) {
        readySince.remove(pid);
    }

    /** Prioridad usada para decidir: la base, mejorada por aging si está activo. */
    int effectivePriority(ProcessControlBlock pcb) {
        int basePriority = pcb.priority();
        int interval = configuration.agingInterval();
        if (interval == 0) {
            return basePriority;
        }
        long enteredReady = readySince.computeIfAbsent(pcb.processId(), pid -> now);
        long waited = Math.max(now - enteredReady, 0);
        long improvement = waited / interval;
        long aged = basePriority - improvement;
        return (int) Math.max(aged, ProcessControlBlock.MIN_PRIORITY);
    }

    /** Menor resultado = mejor candidato: menor prioridad efectiva, luego menor Arrival y menor PID. */
    private int compare(ProcessControlBlock first, ProcessControlBlock second) {
        int byPriority = Integer.compare(effectivePriority(first), effectivePriority(second));
        if (byPriority != 0) return byPriority;
        int byArrival = Long.compare(first.logicalTimes().arrivalTime(), second.logicalTimes().arrivalTime());
        if (byArrival != 0) return byArrival;
        return Integer.compare(first.processId(), second.processId());
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
