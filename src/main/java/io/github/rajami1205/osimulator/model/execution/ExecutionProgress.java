package io.github.rajami1205.osimulator.model.execution;

import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import java.util.Optional;

/** Progreso transitorio de sesión; sólo el engine realiza sus transiciones. */
public final class ExecutionProgress {
    private ProcessControlBlock process;
    private CpuRegisters<Instruction> cpu;
    private MainMemory memory;
    private Instruction instruction;
    private int startPc;
    private int consumedTicks;

    public boolean isIdle() { return instruction == null; }
    public Optional<Instruction> instruction() { return Optional.ofNullable(instruction); }
    public int consumedTicks() { return consumedTicks; }

    void validateContext(MainMemory memory, CpuRegisters<Instruction> cpu, ProcessControlBlock pcb) {
        if (!isIdle() && (process != pcb || this.cpu != cpu || this.memory != memory
                || pcb.programCounter() != startPc || cpu.programCounter() != startPc
                || cpu.instructionRegister().orElse(null) != instruction)) {
            throw new ExecutionEngineException("In-flight instruction context does not match");
        }
    }

    void begin(MainMemory memory, CpuRegisters<Instruction> cpu, ProcessControlBlock pcb, Instruction instruction) {
        this.memory = memory;
        this.cpu = cpu;
        process = pcb;
        startPc = pcb.programCounter();
        this.instruction = instruction;
        consumedTicks = 0;
    }

    boolean consumeTick() { return ++consumedTicks == instruction.executionWeight().ticks(); }

    void clear() {
        process = null;
        cpu = null;
        memory = null;
        instruction = null;
        startPc = 0;
        consumedTicks = 0;
    }
}
