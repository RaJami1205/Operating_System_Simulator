package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.Objects;
import java.util.Optional;

/**
 * Selecciona la cabeza READY sin consumirla ni hacer dispatch; no usa priority y reporta inconsistencias
 * en lugar de reparar la cola.
 */
public final class FcfsProcessScheduler implements ProcessScheduler {
    private final ReadyQueue readyQueue;
    private final ProcessTable processes;

    /** Recibe ReadyQueue y ProcessTable canónicos no nulos para validar el candidato sin consumirlo. */
    public FcfsProcessScheduler(ReadyQueue readyQueue, ProcessTable processes) {
        this.readyQueue = Objects.requireNonNull(readyQueue, "readyQueue must not be null");
        this.processes = Objects.requireNonNull(processes, "processes must not be null");
    }

    /**
     * Valida que la cabeza exista en ProcessTable y esté READY; devuelve su PID sin consumir la cola ni
     * realizar dispatch.
     */
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
