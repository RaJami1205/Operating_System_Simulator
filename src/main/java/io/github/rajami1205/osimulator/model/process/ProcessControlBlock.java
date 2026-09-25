package io.github.rajami1205.osimulator.model.process;

import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessConfigurationException;
import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessProgramCounterException;
import java.util.Objects;
import java.util.Optional;
import io.github.rajami1205.osimulator.model.cpu.CpuContext;
import io.github.rajami1205.osimulator.model.instruction.Instruction;

/**
 * Representa la identidad, ubicación del programa y estado inicial del proceso.
 */
public final class ProcessControlBlock {

    private final int processId;
    private final ProcessMemoryBounds memoryBounds;
    private final ProcessStack stack = new ProcessStack();
    private final OpenFileTable openFiles = new OpenFileTable();
    private ProcessAccounting accounting = ProcessAccounting.initial();
    private ProcessState state = ProcessState.NEW;
    private CpuContext<Instruction> cpuContext = CpuContext.initial();
    private int priority;
    private Optional<PcbAddress> nextPcbAddress = Optional.empty();
    // Valida y fija los metadatos del proceso con su PC inicial y estado NEW.
    public ProcessControlBlock(
            int processId,
            int programStartAddress,
            int instructionCount
    ) {
        validateProcessId(processId);
        this.processId = processId;
        this.memoryBounds = new ProcessMemoryBounds(programStartAddress, instructionCount);
    }

    public ProcessMemoryBounds memoryBounds() { return memoryBounds; }
    public CpuContext<Instruction> cpuContext() { return cpuContext; }
    public ProcessStack stack() { return stack; }
    public OpenFileTable openFiles() { return openFiles; }
    public ProcessAccounting accounting() { return accounting; }
    public int priority() { return priority; }
    public Optional<PcbAddress> nextPcbAddress() { return nextPcbAddress; }

    public void replaceCpuContext(CpuContext<Instruction> context) {
        Objects.requireNonNull(context, "context must not be null");
        validateOperationalProgramCounter(context.programCounter());
        cpuContext = context;
    }

    public void replaceAccounting(ProcessAccounting accounting) {
        this.accounting = Objects.requireNonNull(accounting, "accounting must not be null");
    }

    public void setPriority(int priority) { this.priority = priority; }

    public void setNextPcbAddress(Optional<PcbAddress> address) {
        nextPcbAddress = Objects.requireNonNull(address, "address must not be null");
    }
    // Expone el identificador del proceso.
    public int processId() {
        return processId;
    }

    // Expone la dirección inicial del programa cargado.
    public int programStartAddress() {
        return memoryBounds.base();
    }

    // Expone la cantidad de instrucciones del proceso.
    public int instructionCount() {
        return memoryBounds.limit();
    }

    // Expone el límite exclusivo del programa en memoria.
    public int programEndAddressExclusive() {
        return memoryBounds.endExclusive();
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
        return cpuContext.programCounter();
    }

    // Mantiene el PC guardado dentro del programa o en su límite final exclusivo.
    public void setProgramCounter(int programCounter) {
        validateOperationalProgramCounter(programCounter);
        cpuContext = cpuContext.withProgramCounter(programCounter);
    }

    // El PC lógico admite Limit únicamente como marcador terminal.
    private void validateOperationalProgramCounter(int programCounter) {
        if (programCounter < 0 || programCounter > memoryBounds.limit()) {
            throw new InvalidProcessProgramCounterException(
                    "Process Program Counter must be between 0"
                            + " and " + memoryBounds.limit() + ": " + programCounter);
        }
    }
    // Exige un identificador positivo para el proceso.
    private static void validateProcessId(int processId) {
        if (processId <= 0) {
            throw new InvalidProcessConfigurationException(
                    "Process ID must be greater than zero: " + processId
            );
        }
    }

}
