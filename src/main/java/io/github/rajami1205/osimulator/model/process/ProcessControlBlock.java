package io.github.rajami1205.osimulator.model.process;

import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessConfigurationException;
import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessProgramCounterException;
import java.util.Objects;

/**
 * Representa la identidad, ubicación del programa y estado inicial del proceso.
 */
public final class ProcessControlBlock {

    private final int processId;
    private final int programStartAddress;
    private final int instructionCount;
    private final int programEndAddressExclusive;
    private ProcessState state;
    private int programCounter;

    // Valida y fija los metadatos del proceso con su PC inicial y estado NEW.
    public ProcessControlBlock(
            int processId,
            int programStartAddress,
            int instructionCount
    ) {
        validateProcessId(processId);
        validateProgramStartAddress(programStartAddress);
        validateInstructionCount(instructionCount);

        long endAddressExclusive = (long) programStartAddress + instructionCount;
        if (endAddressExclusive > Integer.MAX_VALUE) {
            throw new InvalidProcessConfigurationException(
                    "Program end address exceeds the maximum supported address: "
                            + endAddressExclusive
            );
        }

        this.processId = processId;
        this.programStartAddress = programStartAddress;
        this.instructionCount = instructionCount;
        this.programEndAddressExclusive = (int) endAddressExclusive;
        this.state = ProcessState.NEW;
        this.programCounter = programStartAddress;
    }

    // Expone el identificador del proceso.
    public int processId() {
        return processId;
    }

    // Expone la dirección inicial del programa cargado.
    public int programStartAddress() {
        return programStartAddress;
    }

    // Expone la cantidad de instrucciones del proceso.
    public int instructionCount() {
        return instructionCount;
    }

    // Expone el límite exclusivo del programa en memoria.
    public int programEndAddressExclusive() {
        return programEndAddressExclusive;
    }

    // Expone el estado actual del proceso.
    public ProcessState state() {
        return state;
    }

    // Actualiza el estado del proceso rechazando valores nulos.
    public void changeState(ProcessState newState) {
        ProcessState nonNullState = Objects.requireNonNull(
                newState,
                "newState must not be null"
        );
        state = nonNullState;
    }

    // Expone el PC guardado para el proceso.
    public int programCounter() {
        return programCounter;
    }

    // Mantiene el PC guardado dentro del programa o en su límite final exclusivo.
    public void setProgramCounter(int programCounter) {
        if (programCounter < programStartAddress
                || programCounter > programEndAddressExclusive) {
            throw new InvalidProcessProgramCounterException(
                    "Process Program Counter must be between "
                            + programStartAddress
                            + " and "
                            + programEndAddressExclusive
                            + ": "
                            + programCounter
            );
        }

        this.programCounter = programCounter;
    }

    // Exige un identificador positivo para el proceso.
    private static void validateProcessId(int processId) {
        if (processId <= 0) {
            throw new InvalidProcessConfigurationException(
                    "Process ID must be greater than zero: " + processId
            );
        }
    }

    // Impide direcciones iniciales negativas.
    private static void validateProgramStartAddress(int programStartAddress) {
        if (programStartAddress < 0) {
            throw new InvalidProcessConfigurationException(
                    "Program start address must not be negative: " + programStartAddress
            );
        }
    }

    // Exige que el proceso contenga al menos una instrucción.
    private static void validateInstructionCount(int instructionCount) {
        if (instructionCount <= 0) {
            throw new InvalidProcessConfigurationException(
                    "Instruction count must be greater than zero: " + instructionCount
            );
        }
    }
}
