package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.Objects;
import java.util.Optional;

/** FCFS consulta exclusivamente la cabeza; inconsistencias se reportan sin reparar la cola. */
public final class FcfsProcessScheduler implements ProcessScheduler {
    private final ReadyQueue readyQueue;
    private final ProcessTable processes;

    public FcfsProcessScheduler(ReadyQueue readyQueue, ProcessTable processes) {
        this.readyQueue = Objects.requireNonNull(readyQueue, "readyQueue must not be null");
        this.processes = Objects.requireNonNull(processes, "processes must not be null");
    }

    @Override
    public Optional<Integer> selectNext() {
        return readyQueue.peek().map(pid -> {
            var pcb = processes.find(pid).orElseThrow(
                    () -> new IllegalStateException("Queued PID is not resident: " + pid));
            if (pcb.state() != ProcessState.READY) {
                throw new IllegalStateException("Queued PID is not READY: " + pid);
            }
            return pid;
        });
    }
}
