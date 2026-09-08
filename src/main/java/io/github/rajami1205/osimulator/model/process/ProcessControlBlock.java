package io.github.rajami1205.osimulator.model.process;

import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessConfigurationException;
import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessProgramCounterException;
import java.util.Objects;

/**
 * Process identity, program placement, and initial runtime state.
 */
public final class ProcessControlBlock {

    private final int processId;
    private final int programStartAddress;
    private final int instructionCount;
    private final int programEndAddressExclusive;
    private ProcessState state;
    private int programCounter;

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

    public int processId() {
        return processId;
    }

    public int programStartAddress() {
        return programStartAddress;
    }

    public int instructionCount() {
        return instructionCount;
    }

    public int programEndAddressExclusive() {
        return programEndAddressExclusive;
    }

    public ProcessState state() {
        return state;
    }

    public void changeState(ProcessState newState) {
        ProcessState nonNullState = Objects.requireNonNull(
                newState,
                "newState must not be null"
        );
        state = nonNullState;
    }

    public int programCounter() {
        return programCounter;
    }

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

    private static void validateProcessId(int processId) {
        if (processId <= 0) {
            throw new InvalidProcessConfigurationException(
                    "Process ID must be greater than zero: " + processId
            );
        }
    }

    private static void validateProgramStartAddress(int programStartAddress) {
        if (programStartAddress < 0) {
            throw new InvalidProcessConfigurationException(
                    "Program start address must not be negative: " + programStartAddress
            );
        }
    }

    private static void validateInstructionCount(int instructionCount) {
        if (instructionCount <= 0) {
            throw new InvalidProcessConfigurationException(
                    "Instruction count must be greater than zero: " + instructionCount
            );
        }
    }
}
