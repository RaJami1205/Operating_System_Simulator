package io.github.rajami1205.osimulator.model.scheduling;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** Identidades en orden de llegada a READY; Application valida el estado canónico. */
public final class ReadyQueue {
    private final LinkedHashSet<Integer> processes = new LinkedHashSet<>();

    public void enqueue(int processId) {
        validateId(processId);
        if (!processes.add(processId)) throw new IllegalStateException("PID already queued: " + processId);
    }

    public Optional<Integer> peek() {
        return processes.isEmpty() ? Optional.empty() : Optional.of(processes.getFirst());
    }

    public Optional<Integer> poll() {
        return processes.isEmpty() ? Optional.empty() : Optional.of(processes.removeFirst());
    }

    public boolean remove(int processId) {
        validateId(processId);
        return processes.remove(processId);
    }

    public List<Integer> entries() { return List.copyOf(processes); }

    private static void validateId(int processId) {
        if (processId <= 0) throw new IllegalArgumentException("Process ID must be positive");
    }
}
