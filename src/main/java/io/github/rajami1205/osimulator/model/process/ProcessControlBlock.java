package io.github.rajami1205.osimulator.model.process;

import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessConfigurationException;
import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessProgramCounterException;
import java.util.Objects;
import java.util.Optional;
import io.github.rajami1205.osimulator.model.cpu.CpuContext;
import io.github.rajami1205.osimulator.model.instruction.Instruction;

/**
 * Estado persistente del proceso: contexto guardado, bounds, stack, archivos, accounting y enlace Kernel.
 * El CPU activo permanece separado y priority sólo es metadata para FCFS.
 */
public final class ProcessControlBlock {

    private final int processId;
    private ProcessMemoryBounds memoryBounds;
    private final ProcessStack stack = new ProcessStack();
    private final OpenFileTable openFiles = new OpenFileTable();
    private ProcessAccounting accounting = ProcessAccounting.initial();
    private ProcessState state = ProcessState.NEW;
    private CpuContext<Instruction> cpuContext = CpuContext.initial();
    private int priority;
    private Optional<PcbAddress> nextPcbAddress = Optional.empty();
    /** Valida y fija los metadatos del proceso con su PC inicial y estado NEW. */
    public ProcessControlBlock(
            int processId,
            int programStartAddress,
            int instructionCount
    ) {
        validateProcessId(processId);
        this.processId = processId;
        this.memoryBounds = new ProcessMemoryBounds(programStartAddress, instructionCount);
    }

    /** Devuelve bounds físicos vigentes; rechaza la consulta mientras el proceso está suspended. */
    public ProcessMemoryBounds memoryBounds() {
        if (state == ProcessState.READY_SUSPENDED || state == ProcessState.BLOCKED_SUSPENDED) {
            throw new IllegalStateException("Suspended process has no current MainMemory bounds");
        }
        return memoryBounds;
    }

    /** Publica nueva Base durante swap-in; exige estado suspended y el mismo Limit lógico. */
    public void relocateSuspended(ProcessMemoryBounds bounds) {
        Objects.requireNonNull(bounds, "bounds must not be null");
        if ((state != ProcessState.READY_SUSPENDED && state != ProcessState.BLOCKED_SUSPENDED)
                || bounds.limit() != memoryBounds.limit()) {
            throw new IllegalStateException("Relocation requires suspended state and unchanged limit");
        }
        memoryBounds = bounds;
    }
    /** Expone el contexto inmutable guardado, que puede diferir del CPU mientras el proceso ejecuta. */
    public CpuContext<Instruction> cpuContext() { return cpuContext; }
    /** Entrega el stack propio y mutable del proceso para operaciones del dominio. */
    public ProcessStack stack() { return stack; }
    /** Entrega la tabla de nombres abiertos propia del proceso, no el índice global de storage. */
    public OpenFileTable openFiles() { return openFiles; }
    /** Consulta el record inmutable de timestamps reales y ticks consumidos. */
    public ProcessAccounting accounting() { return accounting; }
    /** Consulta metadata de prioridad que FCFS no usa como criterio de selección. */
    public int priority() { return priority; }
    /** Consulta el enlace opcional a otra dirección Kernel simulada, no una identidad de objeto Java. */
    public Optional<PcbAddress> nextPcbAddress() { return nextPcbAddress; }

    /** Reemplaza el contexto guardado sólo después de validar su PC lógico contra Limit. */
    public void replaceCpuContext(CpuContext<Instruction> context) {
        Objects.requireNonNull(context, "context must not be null");
        validateOperationalProgramCounter(context.programCounter());
        cpuContext = context;
    }

    /** Publica un record de accounting no nulo sin mutar la instancia histórica anterior. */
    public void replaceAccounting(ProcessAccounting accounting) {
        this.accounting = Objects.requireNonNull(accounting, "accounting must not be null");
    }

    /** Actualiza la metadata de prioridad sin reordenar ReadyQueue. */
    public void setPriority(int priority) { this.priority = priority; }

    /** Reemplaza el enlace opcional no nulo; la coordinación valida después su destino canónico. */
    public void setNextPcbAddress(Optional<PcbAddress> address) {
        nextPcbAddress = Objects.requireNonNull(address, "address must not be null");
    }
    /** Expone el identificador del proceso. */
    public int processId() {
        return processId;
    }

    /** Expone la dirección inicial del programa cargado. */
    public int programStartAddress() {
        return memoryBounds().base();
    }

    /** Expone la cantidad de instrucciones del proceso. */
    public int instructionCount() {
        return memoryBounds.limit();
    }

    /** Expone el límite exclusivo del programa en memoria. */
    public int programEndAddressExclusive() {
        return memoryBounds().endExclusive();
    }

    /** Expone el estado actual del proceso. */
    public ProcessState state() {
        return state;
    }

    /**
     * Asigna un estado no nulo; la validación de la transición contextual corresponde a los servicios de
     * Application.
     */
    public void changeState(ProcessState newState) {
        ProcessState nonNullState = Objects.requireNonNull(
                newState,
                "newState must not be null"
        );
        state = nonNullState;
    }

    /** Expone el PC guardado para el proceso. */
    public int programCounter() {
        return cpuContext.programCounter();
    }

    /** Mantiene el PC guardado dentro del programa o en su límite final exclusivo. */
    public void setProgramCounter(int programCounter) {
        validateOperationalProgramCounter(programCounter);
        cpuContext = cpuContext.withProgramCounter(programCounter);
    }

    /** El PC lógico admite Limit únicamente como marcador terminal. */
    private void validateOperationalProgramCounter(int programCounter) {
        if (programCounter < 0 || programCounter > memoryBounds.limit()) {
            throw new InvalidProcessProgramCounterException(
                    "Process Program Counter must be between 0"
                            + " and " + memoryBounds.limit() + ": " + programCounter);
        }
    }
    /** Exige un identificador positivo para el proceso. */
    private static void validateProcessId(int processId) {
        if (processId <= 0) {
            throw new InvalidProcessConfigurationException(
                    "Process ID must be greater than zero: " + processId
            );
        }
    }

}
