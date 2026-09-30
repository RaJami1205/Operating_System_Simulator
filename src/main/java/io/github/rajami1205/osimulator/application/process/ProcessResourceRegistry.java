package io.github.rajami1205.osimulator.application.process;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Single session ownership registry shared by admission and swapping. */
public final class ProcessResourceRegistry {
    private final Map<Integer, ProcessResources> resources = new HashMap<>();
    public Optional<ProcessResources> find(int pid) { return Optional.ofNullable(resources.get(pid)); }
    public Map<Integer, ProcessResources> entries() { return Map.copyOf(resources); }
    public void register(int pid, ProcessResources value) {
        if (pid <= 0) throw new IllegalArgumentException("PID must be positive");
        Objects.requireNonNull(value);
        if (resources.putIfAbsent(pid, value) != null) throw new IllegalStateException("Resources already registered");
    }
    public boolean remove(int pid) { return resources.remove(pid) != null; }
    void replace(int pid, ProcessResources expected, ProcessResources replacement) {
        Objects.requireNonNull(replacement);
        if (resources.get(pid) != expected) throw new IllegalStateException("Process resources changed during transfer");
        resources.put(pid, replacement);
    }
}
