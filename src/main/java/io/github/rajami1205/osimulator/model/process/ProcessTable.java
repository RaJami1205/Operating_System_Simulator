package io.github.rajami1205.osimulator.model.process;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PCBs canónicos residentes en orden de admisión; no administra recursos de memoria. */
public final class ProcessTable {
    public static final int MAX_ADMITTED_PROCESSES = 5;
    private final LinkedHashMap<Integer, ProcessControlBlock> processes = new LinkedHashMap<>();

    public void register(ProcessControlBlock pcb) {
        Objects.requireNonNull(pcb, "pcb must not be null");
        if (processes.containsKey(pcb.processId())) throw new IllegalArgumentException("Duplicate PID: " + pcb.processId());
        if (isFull()) throw new IllegalStateException("Resident process capacity reached");
        processes.put(pcb.processId(), pcb);
    }

    public Optional<ProcessControlBlock> find(int processId) {
        validateId(processId);
        return Optional.ofNullable(processes.get(processId));
    }

    /** Vista estructural inmutable; las entidades canónicas no son snapshots de Presentation. */
    public List<ProcessControlBlock> entries() { return List.copyOf(processes.values()); }
    public int size() { return processes.size(); }
    public boolean isFull() { return size() >= MAX_ADMITTED_PROCESSES; }
    public Optional<ProcessControlBlock> last() {
        var last = processes.lastEntry();
        return last == null ? Optional.empty() : Optional.of(last.getValue());
    }

    /** Retira sólo la publicación; rollback del caller administra enlaces y recursos. */
    public boolean remove(int processId) {
        validateId(processId);
        return processes.remove(processId) != null;
    }

    private static void validateId(int processId) {
        if (processId <= 0) throw new IllegalArgumentException("Process ID must be positive");
    }
}
