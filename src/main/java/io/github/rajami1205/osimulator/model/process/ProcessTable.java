package io.github.rajami1205.osimulator.model.process;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * PCBs canónicos admitidos, incluidos suspended, en orden de admisión y con límite cinco. No sustituye
 * ReadyQueue ni administra allocations.
 */
public final class ProcessTable {
    public static final int MAX_ADMITTED_PROCESSES = 5;
    private final LinkedHashMap<Integer, ProcessControlBlock> processes = new LinkedHashMap<>();

    /** Publica el PCB canónico sin PID duplicado y sin superar cinco procesos admitidos. */
    public void register(ProcessControlBlock pcb) {
        Objects.requireNonNull(pcb, "pcb must not be null");
        if (processes.containsKey(pcb.processId())) throw new IllegalArgumentException("Duplicate PID: " + pcb.processId());
        if (isFull()) throw new IllegalStateException("Resident process capacity reached");
        processes.put(pcb.processId(), pcb);
    }

    /** Busca un PID positivo y devuelve ausencia si no está admitido. */
    public Optional<ProcessControlBlock> find(int processId) {
        validateId(processId);
        return Optional.ofNullable(processes.get(processId));
    }

    /** Vista estructural inmutable; las entidades canónicas no son snapshots de Presentation. */
    public List<ProcessControlBlock> entries() { return List.copyOf(processes.values()); }
    /** Cuenta PCBs admitidos, incluidos los suspended hasta su retirada. */
    public int size() { return processes.size(); }
    /** Indica si se alcanzó el límite de cinco procesos; no considera la cantidad de Jobs. */
    public boolean isFull() { return size() >= MAX_ADMITTED_PROCESSES; }
    /** Devuelve el último PCB en orden de admisión para mantener la cadena Kernel. */
    public Optional<ProcessControlBlock> last() {
        var last = processes.lastEntry();
        return last == null ? Optional.empty() : Optional.of(last.getValue());
    }

    /** Retira sólo la publicación; rollback del caller administra enlaces y recursos. */
    public boolean remove(int processId) {
        validateId(processId);
        return processes.remove(processId) != null;
    }

    /** Exige PID positivo antes de consultar o retirar la publicación. */
    private static void validateId(int processId) {
        if (processId <= 0) throw new IllegalArgumentException("Process ID must be positive");
    }
}
