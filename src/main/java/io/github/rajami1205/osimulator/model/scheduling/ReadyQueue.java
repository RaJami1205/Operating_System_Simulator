package io.github.rajami1205.osimulator.model.scheduling;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.ProcessTable;

/** Identidades en orden de llegada a READY; Application valida el estado canónico. */
public final class ReadyQueue {
    private final LinkedHashSet<Integer> processes = new LinkedHashSet<>();
    private final CpuId cpuId;
    private final ProcessTable table;

    /** FIFO independiente para usos históricos; los nodos siempre emplean el constructor vinculado. */
    public ReadyQueue() { cpuId = null; table = null; }

    public ReadyQueue(CpuId cpuId, ProcessTable table) {
        this.cpuId = Objects.requireNonNull(cpuId);
        this.table = Objects.requireNonNull(table);
    }

    private void validateAffinity(int pid) {
        if (table != null && !table.find(pid).orElseThrow(
                () -> new IllegalStateException("Unknown queued process")).cpuAffinity().equals(cpuId)) {
            throw new IllegalStateException("Process belongs to another CPU");
        }
    }

    /** Impide conectar una cola local a servicios de otra CPU o tabla. */
    public void requireBinding(CpuId expectedCpu, ProcessTable expectedTable) {
        Objects.requireNonNull(expectedCpu); Objects.requireNonNull(expectedTable);
        if (table != null && (table != expectedTable || !cpuId.equals(expectedCpu))) {
            throw new IllegalArgumentException("ReadyQueue belongs to another CPU or process table");
        }
    }

    /** Añade PID positivo al final del FIFO; rechaza duplicados sin decidir el estado del PCB. */
    public void enqueue(int processId) {
        validateId(processId);
        validateAffinity(processId);
        if (!processes.add(processId)) throw new IllegalStateException("PID already queued: " + processId);
    }

    /** Consulta la cabeza sin consumirla; devuelve ausencia si no hay candidatos. */
    public Optional<Integer> peek() {
        return processes.isEmpty() ? Optional.empty() : Optional.of(processes.getFirst());
    }

    /** Retira y devuelve la cabeza FIFO, o ausencia si la cola está vacía. */
    public Optional<Integer> poll() {
        peek().ifPresent(this::validateAffinity);
        return processes.isEmpty() ? Optional.empty() : Optional.of(processes.removeFirst());
    }

    /** Retira el PID positivo indicado sin reordenar los restantes y devuelve si existía. */
    public boolean remove(int processId) {
        validateId(processId);
        validateAffinity(processId);
        return processes.remove(processId);
    }

    /** Devuelve una copia inmutable de los PID en orden de llegada a READY. */
    public List<Integer> entries() { return List.copyOf(processes); }

    /** Rechaza PID no positivo antes de modificar membresía de la cola. */
    private static void validateId(int processId) {
        if (processId <= 0) throw new IllegalArgumentException("Process ID must be positive");
    }
}
