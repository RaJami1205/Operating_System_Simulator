package io.github.rajami1205.osimulator.application.cpu;

import io.github.rajami1205.osimulator.application.process.Dispatcher;
import io.github.rajami1205.osimulator.application.process.ProcessResourceRegistry;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.execution.CpuTickCounter;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import io.github.rajami1205.osimulator.model.scheduling.*;
import java.util.Objects;

/** Recursos locales; no ejecuta instrucciones ni posee reloj global o contexto PCB adicional. */
public final class CpuNode {
    private final CpuId id;
    private final CpuRegisters<Instruction> registers = new CpuRegisters<>();
    private final CpuTickCounter tickCounter = new CpuTickCounter();
    private final ReadyQueue ready;
    private final SuspendedReadyQueue suspended;
    private final FcfsProcessScheduler scheduler;
    private final Dispatcher dispatcher;
    private final ProcessTable processes;
    private final MainMemory memory;
    private final ProcessResourceRegistry resources;

    public CpuNode(CpuId id, ProcessTable processes, MainMemory memory, ProcessResourceRegistry resources) {
        this.id = Objects.requireNonNull(id);
        this.processes = Objects.requireNonNull(processes);
        this.memory = Objects.requireNonNull(memory);
        this.resources = Objects.requireNonNull(resources);
        ready = new ReadyQueue(id, processes);
        suspended = new SuspendedReadyQueue(id, processes);
        scheduler = new FcfsProcessScheduler(id, ready, processes);
        dispatcher = new Dispatcher(id, registers, processes, ready, resources, memory);
    }

    public CpuId id() { return id; }
    public CpuRegisters<Instruction> registers() { return registers; }
    public CpuTickCounter tickCounter() { return tickCounter; }
    public ReadyQueue readyQueue() { return ready; }
    public SuspendedReadyQueue suspendedReadyQueue() { return suspended; }
    public FcfsProcessScheduler scheduler() { return scheduler; }
    public Dispatcher dispatcher() { return dispatcher; }

    void requireSharedResources(ProcessTable table, MainMemory mainMemory, ProcessResourceRegistry registry) {
        if (processes != table || memory != mainMemory || resources != registry) {
            throw new IllegalArgumentException("CPU nodes must share canonical session resources");
        }
    }

    void requireSameSession(CpuNode other) { requireSharedResources(other.processes, other.memory, other.resources); }
    ProcessTable processes() { return processes; }
}
