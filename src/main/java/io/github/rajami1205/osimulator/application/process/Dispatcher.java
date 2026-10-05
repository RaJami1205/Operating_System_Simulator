package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.scheduling.ReadyQueue;
import java.util.Objects;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import java.util.Optional;

/**
 * Mantiene un único owner del CPU. Restaura y guarda contextos sin consumir ticks; la selección
 * corresponde al Scheduler.
 */
public final class Dispatcher {
    private final CpuRegisters<Instruction> cpu;
    private final ProcessTable table;
    private final ReadyQueue ready;
    private final ProcessResourceRegistry resources;
    private ProcessControlBlock owner;
    private final MainMemory memory;

    /** Recibe CPU, tabla, cola y recursos de la misma sesión; comienza sin owner. */
    public Dispatcher(CpuRegisters<Instruction> cpu, ProcessTable table, ReadyQueue ready,
            ProcessResourceRegistry resources, MainMemory memory) {
        this.memory = Objects.requireNonNull(memory);
        this.cpu = Objects.requireNonNull(cpu);
        this.table = Objects.requireNonNull(table);
        this.ready = Objects.requireNonNull(ready);
        this.resources = Objects.requireNonNull(resources);
    }

    /**
     * Consulta el PCB canónico que posee el CPU, ausente entre ejecuciones; no es una vista para
     * Presentation.
     */
    public Optional<ProcessControlBlock> owner() { return Optional.ofNullable(owner); }

    /**
     * Valida cabeza READY, bounds y handles USER/Kernel antes de restaurar contexto y marcar RUNNING; no
     * consume ticks.
     */
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
        memory.validateUserAllocation(resident.allocation(), pcb.memoryBounds());
        memory.validatePcbAllocation(resource.kernel(), pcb);
        cpu.restore(pcb.cpuContext());
        ready.poll();
        pcb.changeState(ProcessState.RUNNING);
        owner = pcb;
    }

    /**
     * Guarda el CPU en el PCB y libera ownership sólo si el owner está BLOCKED o TERMINATED; no consume
     * ticks ni selecciona sucesor.
     */
    public void release() {
        if (owner == null || (owner.state() != ProcessState.BLOCKED && owner.state() != ProcessState.TERMINATED)) {
            throw new IllegalStateException("Only a blocked/terminated owner may release CPU");
        }
        owner.replaceCpuContext(cpu.snapshot());
        owner = null;
    }
}
