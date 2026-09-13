package io.github.rajami1205.osimulator.application.program;

import io.github.rajami1205.osimulator.application.program.exception.ProgramLoadException;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.memory.Memory;
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
            Memory<Instruction> memory,
            int processId,
            List<Instruction> instructions
    ) {
        Memory<Instruction> nonNullMemory = Objects.requireNonNull(
                memory,
                "memory must not be null"
        );
        List<Instruction> program = List.copyOf(
                Objects.requireNonNull(instructions, "instructions must not be null")
        );

        if (program.isEmpty()) {
            throw new ProgramLoadException("Program must contain at least one instruction");
        }

        int userStartAddress = nonNullMemory.configuration().userStartAddress();
        int userPositions = nonNullMemory.configuration().userPositions();
        if (program.size() > userPositions) {
            throw new ProgramLoadException(
                    "Program contains "
                            + program.size()
                            + " instructions, but User Memory has capacity for "
                            + userPositions
            );
        }

        ProcessControlBlock pcb = createProcessControlBlock(
                processId,
                userStartAddress,
                program.size()
        );
        validateTargetRangeIsEmpty(
                nonNullMemory,
                userStartAddress,
                program.size()
        );

        nonNullMemory.writeUserBlock(userStartAddress, program);
        pcb.changeState(ProcessState.READY);
        return pcb;
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

    // Comprueba que la carga no sobrescriba posiciones ocupadas.
    private void validateTargetRangeIsEmpty(
            Memory<Instruction> memory,
            int startAddress,
            int instructionCount
    ) {
        for (int offset = 0; offset < instructionCount; offset++) {
            int address = startAddress + offset;
            if (!memory.isEmpty(address)) {
                throw new ProgramLoadException(
                        "Program target memory address is already occupied: " + address
                );
            }
        }
    }
}
