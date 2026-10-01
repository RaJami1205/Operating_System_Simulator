package io.github.rajami1205.osimulator.model.scheduling;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/** FIFO of arrivals into READY_SUSPENDED; owns no images or allocation handles. */
public final class SuspendedReadyQueue {
    private final LinkedHashSet<Integer> processes = new LinkedHashSet<>();
    public void enqueue(int pid) {
        if (pid <= 0) throw new IllegalArgumentException("PID must be positive");
        if (!processes.add(pid)) throw new IllegalStateException("Suspended PID already queued");
    }
    public Optional<Integer> peek() { return processes.isEmpty() ? Optional.empty() : Optional.of(processes.getFirst()); }
    public boolean remove(int pid) { return processes.remove(pid); }
    public List<Integer> entries() { return List.copyOf(processes); }
}
