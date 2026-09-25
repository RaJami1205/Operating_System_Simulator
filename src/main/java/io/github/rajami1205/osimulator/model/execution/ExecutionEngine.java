package io.github.rajami1205.osimulator.model.execution;

import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.cpu.exception.InvalidRegisterValueException;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.instruction.AddInstruction;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.MovInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import java.util.Objects;

/**
 * Ejecuta un paso de instrucción semántica para un proceso simulado.
 */
public final class ExecutionEngine {

    // Ejecuta como máximo una instrucción y actualiza CPU, PC y estado del proceso.
    public void executeNext(
            MainMemory memory,
            CpuRegisters<Instruction> cpu,
            ProcessControlBlock pcb
    ) {
        Objects.requireNonNull(memory, "memory must not be null");
        Objects.requireNonNull(cpu, "cpu must not be null");
        Objects.requireNonNull(pcb, "pcb must not be null");

        validateExecutableState(pcb);

        int currentProgramCounter = pcb.programCounter();
        int instructionCount = pcb.instructionCount();
        if (currentProgramCounter == instructionCount) {
            cpu.setProgramCounter(instructionCount);
            pcb.changeState(ProcessState.TERMINATED);
            return;
        }

        Instruction instruction;
        try {
            instruction = memory.readInstruction(pcb.memoryBounds(), currentProgramCounter);
        } catch (MemoryProtectionException exception) {
            throw new ExecutionEngineException("Unable to fetch process instruction", exception);
        }

        cpu.setProgramCounter(currentProgramCounter);
        cpu.loadInstructionRegister(instruction);
        if (pcb.state() == ProcessState.READY) {
            pcb.changeState(ProcessState.RUNNING);
        }

        executeInstruction(instruction, cpu);

        int nextProgramCounter = currentProgramCounter + 1;
        cpu.setProgramCounter(nextProgramCounter);
        pcb.setProgramCounter(nextProgramCounter);
        if (nextProgramCounter == instructionCount) {
            pcb.changeState(ProcessState.TERMINATED);
        }
    }

    // Permite ejecutar únicamente procesos READY o RUNNING.
    private void validateExecutableState(ProcessControlBlock pcb) {
        if (pcb.state() != ProcessState.READY && pcb.state() != ProcessState.RUNNING) {
            throw new ExecutionEngineException(
                    "Process state is not executable: " + pcb.state()
            );
        }
    }

    // Aplica la semántica de la instrucción y traduce fallos de rango de la CPU.
    private void executeInstruction(
            Instruction instruction,
            CpuRegisters<Instruction> cpu
    ) {
        try {
            switch (instruction) {
                case MovInstruction mov ->
                        cpu.writeRegister(mov.destination(), mov.immediate());
                case LoadInstruction load ->
                        cpu.writeAccumulator(cpu.readRegister(load.source()));
                case StoreInstruction store ->
                        cpu.writeRegister(store.destination(), cpu.accumulator());
                case AddInstruction add ->
                        cpu.writeAccumulator(
                                cpu.accumulator() + cpu.readRegister(add.source())
                        );
                case SubInstruction sub ->
                        cpu.writeAccumulator(
                                cpu.accumulator() - cpu.readRegister(sub.source())
                        );
            }
        } catch (InvalidRegisterValueException exception) {
            throw new ExecutionEngineException(
                    "Instruction result violates the CPU register range",
                    exception
            );
        }
    }
}
