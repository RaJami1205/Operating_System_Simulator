package io.github.rajami1205.osimulator.application.program;

import io.github.rajami1205.osimulator.application.program.exception.ProgramLoadException;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.memory.MemoryAllocation;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryAllocationException;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessConfigurationException;
import java.util.List;
import java.util.Objects;

/**
 * Coordina la carga de un programa semántico en la memoria User simulada.
 */
public final class ProgramLoader {

    // Valida el programa y lo carga en User Memory con un PCB en READY.
    public ProcessControlBlock load(
            MainMemory memory,
            int processId,
            List<Instruction> instructions
    ) {
        MainMemory nonNullMemory = Objects.requireNonNull(
                memory,
                "memory must not be null"
        );
        List<Instruction> program = List.copyOf(
                Objects.requireNonNull(instructions, "instructions must not be null")
        );

        if (program.isEmpty()) {
            throw new ProgramLoadException("Program must contain at least one instruction");
        }

        MemoryAllocation allocation;
        try {
            allocation = nonNullMemory.allocateUser(program.size());
        } catch (MemoryAllocationException exception) {
            throw new ProgramLoadException("Unable to allocate contiguous User memory", exception);
        }
        try {
            nonNullMemory.writeUserBlock(allocation, program);
            ProcessControlBlock pcb = createProcessControlBlock(processId, allocation.base(), allocation.size());
            pcb.changeState(ProcessState.READY);
            return pcb;
        } catch (RuntimeException exception) {
            nonNullMemory.release(allocation);
            throw exception;
        }
    }

    // Crea los metadatos del proceso y traduce errores de configuración a errores de carga.
    private ProcessControlBlock createProcessControlBlock(
            int processId,
            int programStartAddress,
            int instructionCount
    ) {
        try {
            return new ProcessControlBlock(
                    processId,
                    programStartAddress,
                    instructionCount
            );
        } catch (InvalidProcessConfigurationException exception) {
            throw new ProgramLoadException(
                    "Unable to create Process Control Block for program load",
                    exception
            );
        }
    }

}
