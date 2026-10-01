package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.scheduling.ReadyQueue;
import java.util.Objects;
import java.util.Optional;

/** Sole CPU owner. Selection policy and execution remain outside this component. */
public final class Dispatcher {
    private final CpuRegisters<Instruction> cpu;
    private final ProcessTable table;
    private final ReadyQueue ready;
    private final ProcessResourceRegistry resources;
    private ProcessControlBlock owner;

    public Dispatcher(CpuRegisters<Instruction> cpu, ProcessTable table, ReadyQueue ready,
            ProcessResourceRegistry resources) {
        this.cpu = Objects.requireNonNull(cpu);
        this.table = Objects.requireNonNull(table);
        this.ready = Objects.requireNonNull(ready);
        this.resources = Objects.requireNonNull(resources);
    }

    public Optional<ProcessControlBlock> owner() { return Optional.ofNullable(owner); }

    public void dispatch(int pid) {
        if (owner != null) throw new IllegalStateException("CPU already owned");
        var pcb = table.find(pid).orElseThrow(() -> new IllegalStateException("Unknown selected process"));
        var resource = resources.find(pid).orElseThrow(() -> new IllegalStateException("Missing process resources"));
        if (!ready.peek().equals(Optional.of(pid)) || pcb.state() != ProcessState.READY
                || !(resource.residence() instanceof UserImageResidence.Resident resident)
                || resident.allocation().base() != pcb.memoryBounds().base()
                || resident.allocation().size() != pcb.instructionCount()
                || pcb.programCounter() >= pcb.instructionCount()
                || table.entries().stream().anyMatch(p -> p.state() == ProcessState.RUNNING)) {
            throw new IllegalStateException("Invalid dispatch candidate or CPU ownership");
        }
        cpu.restore(pcb.cpuContext());
        ready.poll();
        pcb.changeState(ProcessState.RUNNING);
        owner = pcb;
    }

    public void release() {
        if (owner == null || (owner.state() != ProcessState.BLOCKED && owner.state() != ProcessState.TERMINATED)) {
            throw new IllegalStateException("Only a blocked/terminated owner may release CPU");
        }
        owner.replaceCpuContext(cpu.snapshot());
        owner = null;
    }
}
