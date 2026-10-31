package io.github.rajami1205.osimulator.application.cpu;

import io.github.rajami1205.osimulator.application.process.ProcessResourceRegistry;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.*;
import java.util.stream.IntStream;

/** Topología ordenada e inmutable en estructura; no coordina ejecución ni consume tiempo. */
public final class CpuTopology {
    private final List<CpuNode> nodes;
    private final ProcessTable processes;

    public CpuTopology(int cpuCount, ProcessTable processes, MainMemory memory, ProcessResourceRegistry resources) {
        this(createNodes(cpuCount, processes, memory, resources));
    }

    public CpuTopology(List<CpuNode> nodes) {
        Objects.requireNonNull(nodes);
        validateCount(nodes.size());
        this.nodes = nodes.stream().map(Objects::requireNonNull)
                .sorted(Comparator.comparingInt(node -> node.id().value())).toList();
        var first = this.nodes.getFirst();
        processes = first.processes();
        for (int i = 0; i < this.nodes.size(); i++) {
            var node = this.nodes.get(i);
            if (node.id().value() != i) throw new IllegalArgumentException("CPU IDs must be unique and contiguous from zero");
            node.requireSameSession(first);
        }
        if (processes.capacity() > ProcessTable.MAX_ADMITTED_PROCESSES * nodes.size()) {
            throw new IllegalArgumentException("Process capacity exceeds topology capacity");
        }
        processes.entries().forEach(pcb -> node(pcb.cpuAffinity()));
    }

    private static List<CpuNode> createNodes(int count, ProcessTable table, MainMemory memory, ProcessResourceRegistry resources) {
        validateCount(count);
        return IntStream.range(0, count).mapToObj(id -> new CpuNode(new CpuId(id), table, memory, resources)).toList();
    }

    private static void validateCount(int count) {
        if (count < 1 || count > CpuId.MAX_CPU_COUNT) throw new IllegalArgumentException("CPU count must be between 1 and 5");
    }

    public List<CpuNode> nodes() { return nodes; }

    public CpuNode node(CpuId id) {
        Objects.requireNonNull(id);
        if (id.value() >= nodes.size()) throw new IllegalArgumentException("CPU is not part of this topology");
        return nodes.get(id.value());
    }

    /** El cupo se deriva de PCB canónicos; no hay reservas ni cursor que deban revertirse. */
    public Optional<CpuNode> leastLoaded() {
        if (processes.isFull()) return Optional.empty();
        return nodes.stream().filter(node -> processes.countForCpu(node.id()) < ProcessTable.MAX_ADMITTED_PROCESSES)
                .min(Comparator.<CpuNode>comparingLong(node -> processes.countForCpu(node.id()))
                        .thenComparingInt(node -> node.id().value()));
    }

    public void requireSharedResources(ProcessTable table, MainMemory memory, ProcessResourceRegistry resources) {
        nodes.getFirst().requireSharedResources(table, memory, resources);
    }
}
