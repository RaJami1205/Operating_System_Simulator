package io.github.rajami1205.osimulator.model.scheduling;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** FIFO de llegada a READY_SUSPENDED; almacena PID, no imágenes ni handles de allocation. */
public final class SuspendedReadyQueue {
    private final LinkedHashSet<Integer> processes = new LinkedHashSet<>();
    /** Añade al final el PID positivo que llegó a READY_SUSPENDED, rechazando duplicados. */
    public void enqueue(int pid) {
        if (pid <= 0) throw new IllegalArgumentException("PID must be positive");
        if (!processes.add(pid)) throw new IllegalStateException("Suspended PID already queued");
    }
    /** Consulta el primer candidato suspended sin retirarlo ni iniciar swap-in. */
    public Optional<Integer> peek() { return processes.isEmpty() ? Optional.empty() : Optional.of(processes.getFirst()); }
    /** Retira el PID si está en la cola y conserva el orden de los demás. */
    public boolean remove(int pid) { return processes.remove(pid); }
    /** Devuelve una copia inmutable del FIFO de procesos READY_SUSPENDED. */
    public List<Integer> entries() { return List.copyOf(processes); }
}
