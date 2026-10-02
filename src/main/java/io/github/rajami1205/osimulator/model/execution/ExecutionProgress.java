package io.github.rajami1205.osimulator.model.execution;

import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import java.util.Optional;
import io.github.rajami1205.osimulator.model.memory.MemoryAllocation;

/** Progreso transitorio de sesión; sólo el engine realiza sus transiciones. */
public final class ExecutionProgress {
    private ProcessControlBlock process;
    private CpuRegisters<Instruction> cpu;
    private MainMemory memory;
    private Instruction instruction;
    private MemoryAllocation allocation;
    private int startPc;
    private int consumedTicks;

    /** Indica que no hay instrucción parcialmente consumida vinculada al CPU. */
    public boolean isIdle() { return instruction == null; }
    /** Consulta la instrucción en curso, ausente cuando no existe progreso activo. */
    public Optional<Instruction> instruction() { return Optional.ofNullable(instruction); }
    /** Devuelve los ticks consumidos por la instrucción actual, no el total del proceso. */
    public int consumedTicks() { return consumedTicks; }

    /**
     * Rechaza cambios de PCB, CPU, memoria, PC, IR o identidad de allocation durante una instrucción
     * parcialmente ejecutada.
     */
    void validateContext(MainMemory memory, CpuRegisters<Instruction> cpu, ProcessControlBlock pcb, MemoryAllocation allocation) {
        if (!isIdle() && (process != pcb || this.cpu != cpu || this.memory != memory
                || pcb.programCounter() != startPc || cpu.programCounter() != startPc
                || cpu.instructionRegister().orElse(null) != instruction || !this.allocation.equals(allocation))) {
            throw new ExecutionEngineException("In-flight instruction context does not match");
        }
    }

    /** Vincula la instrucción y su handle canónico al contexto activo y reinicia sus ticks parciales. */
    void begin(MainMemory memory, CpuRegisters<Instruction> cpu, ProcessControlBlock pcb, Instruction instruction, MemoryAllocation allocation) {
        this.allocation = allocation;
        this.memory = memory;
        this.cpu = cpu;
        process = pcb;
        startPc = pcb.programCounter();
        this.instruction = instruction;
        consumedTicks = 0;
    }

    /** Consume un tick y devuelve true sólo al alcanzar el ExecutionWeight de la instrucción. */
    boolean consumeTick() {
        return ++consumedTicks == instruction.executionWeight().ticks();
    }

    /**
     * Descarta referencias y progreso parcial después de completion o fallo, sin reiniciar registros del
     * CPU.
     */
    void clear() {
        allocation = null;
        process = null;
        cpu = null;
        memory = null;
        instruction = null;
        startPc = 0;
        consumedTicks = 0;
    }
}
