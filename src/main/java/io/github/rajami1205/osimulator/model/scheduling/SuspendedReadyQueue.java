package io.github.rajami1205.osimulator.model.scheduling;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.ProcessTable;

/** FIFO de llegada a READY_SUSPENDED; almacena PID, no imágenes ni handles de allocation. */
public final class SuspendedReadyQueue {
    private final LinkedHashSet<Integer> processes = new LinkedHashSet<>();
    private final CpuId cpuId;
    private final ProcessTable table;

    public SuspendedReadyQueue() { cpuId = null; table = null; }

    public SuspendedReadyQueue(CpuId cpuId, ProcessTable table) {
        this.cpuId = Objects.requireNonNull(cpuId);
        this.table = Objects.requireNonNull(table);
    }

    private void validateAffinity(int pid) {
        if (table != null && !table.find(pid).orElseThrow(
                () -> new IllegalStateException("Unknown suspended process")).cpuAffinity().equals(cpuId)) {
            throw new IllegalStateException("Process belongs to another CPU");
        }
    }

    public void requireBinding(CpuId expectedCpu, ProcessTable expectedTable) {
        Objects.requireNonNull(expectedCpu); Objects.requireNonNull(expectedTable);
        if (table != null && (table != expectedTable || !cpuId.equals(expectedCpu))) {
            throw new IllegalArgumentException("SuspendedReadyQueue belongs to another CPU or process table");
        }
    }
    /** Añade al final el PID positivo que llegó a READY_SUSPENDED, rechazando duplicados. */
    public void enqueue(int pid) {
        if (pid <= 0) throw new IllegalArgumentException("PID must be positive");
        validateAffinity(pid);
        if (!processes.add(pid)) throw new IllegalStateException("Suspended PID already queued");
    }
    /** Consulta el primer candidato suspended sin retirarlo ni iniciar swap-in. */
    public Optional<Integer> peek() { return processes.isEmpty() ? Optional.empty() : Optional.of(processes.getFirst()); }
    /** Retira el PID si está en la cola y conserva el orden de los demás. */
    public boolean remove(int pid) { validateAffinity(pid); return processes.remove(pid); }
    /** Devuelve una copia inmutable del FIFO de procesos READY_SUSPENDED. */
    public List<Integer> entries() { return List.copyOf(processes); }
}
