package io.github.rajami1205.osimulator.model.scheduling;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Identidades en orden de llegada a READY; Application valida el estado canónico. */
public final class ReadyQueue {
    private final LinkedHashSet<Integer> processes = new LinkedHashSet<>();

    /** Añade PID positivo al final del FIFO; rechaza duplicados sin decidir el estado del PCB. */
    public void enqueue(int processId) {
        validateId(processId);
        if (!processes.add(processId)) throw new IllegalStateException("PID already queued: " + processId);
    }

    /** Consulta la cabeza sin consumirla; devuelve ausencia si no hay candidatos. */
    public Optional<Integer> peek() {
        return processes.isEmpty() ? Optional.empty() : Optional.of(processes.getFirst());
    }

    /** Retira y devuelve la cabeza FIFO, o ausencia si la cola está vacía. */
    public Optional<Integer> poll() {
        return processes.isEmpty() ? Optional.empty() : Optional.of(processes.removeFirst());
    }

    /** Retira el PID positivo indicado sin reordenar los restantes y devuelve si existía. */
    public boolean remove(int processId) {
        validateId(processId);
        return processes.remove(processId);
    }

    /** Devuelve una copia inmutable de los PID en orden de llegada a READY. */
    public List<Integer> entries() { return List.copyOf(processes); }

    /** Rechaza PID no positivo antes de modificar membresía de la cola. */
    private static void validateId(int processId) {
        if (processId <= 0) throw new IllegalArgumentException("Process ID must be positive");
    }
}
