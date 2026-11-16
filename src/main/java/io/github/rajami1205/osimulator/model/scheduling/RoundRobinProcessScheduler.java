package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.Objects;
import java.util.Optional;

/**
 * Round Robin: cabeza FIFO de la ReadyQueue local y expulsión del owner cuando completó {@code quantum}
 * instrucciones desde su último dispatch. Sólo las instrucciones completadas consumen quantum. Si el
 * quantum expira y no hay otro proceso READY, el owner continúa con un quantum nuevo.
 */
public final class RoundRobinProcessScheduler implements ProcessScheduler {
    private final CpuId cpuId;
    private final ReadyQueue readyQueue;
    private final ProcessTable processes;
    private final int quantum;
    private int completedInQuantum;

    public RoundRobinProcessScheduler(CpuId cpuId, ReadyQueue readyQueue, ProcessTable processes, int quantum) {
        this.cpuId = Objects.requireNonNull(cpuId, "cpuId must not be null");
        this.readyQueue = Objects.requireNonNull(readyQueue, "readyQueue must not be null");
        this.processes = Objects.requireNonNull(processes, "processes must not be null");
        if (quantum < SimulatorConfiguration.MIN_QUANTUM || quantum > SimulatorConfiguration.MAX_QUANTUM) {
            throw new IllegalArgumentException("Quantum must be between "
                    + SimulatorConfiguration.MIN_QUANTUM + " and " + SimulatorConfiguration.MAX_QUANTUM);
        }
        this.quantum = quantum;
        readyQueue.requireBinding(cpuId, processes);
    }

    public int quantum() { return quantum; }

    @Override
    public Optional<Integer> selectNext() {
        return readyQueue.peek().map(pid -> {
            ProcessControlBlock pcb = processes.find(pid).orElseThrow(
                    () -> new IllegalStateException("Queued PID is not resident: " + pid));
            if (pcb.state() != ProcessState.READY || !pcb.cpuAffinity().equals(cpuId)) {
                throw new IllegalStateException("Queued PID is not READY: " + pid);
            }
            return pid;
        });
    }

    @Override
    public boolean shouldPreempt(ProcessControlBlock owner) {
        Objects.requireNonNull(owner, "owner must not be null");
        if (completedInQuantum < quantum) {
            return false;
        }
        if (readyQueue.peek().isEmpty()) {
            completedInQuantum = 0;
            return false;
        }
        return true;
    }

    @Override
    public void onDispatched(int pid) {
        completedInQuantum = 0;
    }

    @Override
    public void onInstructionCompleted(int pid) {
        completedInQuantum++;
    }
}
