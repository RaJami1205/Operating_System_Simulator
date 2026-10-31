package io.github.rajami1205.osimulator.model.process;

import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessConfigurationException;
import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessProgramCounterException;
import java.util.Objects;
import java.util.Optional;
import io.github.rajami1205.osimulator.model.cpu.CpuContext;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.memory.LogicalAddress;
import io.github.rajami1205.osimulator.model.memory.LogicalCodeRange;

/**
 * Estado persistente del proceso: contexto guardado, bounds, stack, archivos, accounting y enlace Kernel.
 * El CPU activo permanece separado y priority sólo es metadata para FCFS.
 */
public final class ProcessControlBlock {
    /** Base compartida por los espacios lógicos independientes de cada proceso. */
    public static final int LOGICAL_CODE_BASE = 4096;

    private final int processId;
    private final CpuId cpuAffinity;
    private final LogicalCodeRange logicalCodeRange;
    private ProcessMemoryBounds memoryBounds;
    private final ProcessStack stack = new ProcessStack();
    private final OpenFileTable openFiles = new OpenFileTable();
    private ProcessAccounting accounting = ProcessAccounting.initial();
    private LogicalProcessTimes logicalTimes;
    private ProcessState state = ProcessState.NEW;
    private CpuContext<Instruction> cpuContext;
    private int priority;
    private Optional<PcbAddress> nextPcbAddress = Optional.empty();
    /** Valida y fija los metadatos del proceso con su PC inicial y estado NEW. */
    public ProcessControlBlock(
            int processId,
            int programStartAddress,
            int instructionCount
    ) {
        this(processId, programStartAddress, instructionCount, LogicalProcessTimes.admitted(0, 0));
    }

    public ProcessControlBlock(int processId, int programStartAddress, int instructionCount,
            LogicalProcessTimes logicalTimes) {
        this(processId, programStartAddress, instructionCount, logicalTimes, CpuId.ZERO);
    }

    /** Fija afinidad antes de publicar recursos; nunca cambia por dispatch o swapping. */
    public ProcessControlBlock(int processId, int programStartAddress, int instructionCount,
            LogicalProcessTimes logicalTimes, CpuId cpuAffinity) {
        this.cpuAffinity = Objects.requireNonNull(cpuAffinity);
        this.logicalTimes = Objects.requireNonNull(logicalTimes);
        validateProcessId(processId);
        this.processId = processId;
        this.memoryBounds = new ProcessMemoryBounds(programStartAddress, instructionCount);
        this.logicalCodeRange = new LogicalCodeRange(new LogicalAddress(LOGICAL_CODE_BASE), instructionCount);
        this.cpuContext = CpuContext.<Instruction>initial().withProgramCounter(logicalCodeRange.base().value());
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
        validateOperationalProgramCounter(programCounter());
        memoryBounds = bounds;
    }

    /** Rango lógico inmutable, independiente de la residencia. */
    public LogicalCodeRange logicalCodeRange() { return logicalCodeRange; }

    /** Dirección lógica de una posición ejecutable del programa. */
    public int logicalInstructionAddress(int offset) {
        if (offset < 0 || offset >= logicalCodeRange.length()) {
            throw new InvalidProcessProgramCounterException("Invalid instruction offset: " + offset);
        }
        return logicalCodeRange.base().value() + offset;
    }

    /** PC lógico ejecutable, independiente de la residencia. */
    public boolean hasExecutableProgramCounter() {
        return programCounter() >= logicalCodeRange.base().value() && programCounter() < logicalCodeRange.endExclusive().value();
    }

    /** Distingue el marcador terminal lógico, también durante suspensión. */
    public boolean hasTerminalProgramCounter() { return programCounter() == logicalCodeRange.endExclusive().value(); }

    /** Expone el contexto inmutable guardado, que puede diferir del CPU mientras el proceso ejecuta. */
    public CpuContext<Instruction> cpuContext() { return cpuContext; }
    /** Entrega el stack propio y mutable del proceso para operaciones del dominio. */
    public ProcessStack stack() { return stack; }
    /** Entrega la tabla de nombres abiertos propia del proceso, no el índice global de storage. */
    public OpenFileTable openFiles() { return openFiles; }
    /** Consulta el record inmutable de timestamps reales y ticks consumidos. */
    public ProcessAccounting accounting() { return accounting; }
    public LogicalProcessTimes logicalTimes() { return logicalTimes; }
    public CpuId cpuAffinity() { return cpuAffinity; }

    /** Publica un evento previamente validado sin alterar snapshots anteriores. */
    public void recordFirstDispatch(long time) { logicalTimes = logicalTimes.dispatchedAt(time); }

    /** Se invoca únicamente después del commit temporal del tick terminal. */
    public void recordLogicalFinish(long time) { logicalTimes = logicalTimes.finishedAt(time); }
    /** Consulta metadata de prioridad que FCFS no usa como criterio de selección. */
    public int priority() { return priority; }
    /** Consulta el enlace opcional a otra dirección Kernel simulada, no una identidad de objeto Java. */
    public Optional<PcbAddress> nextPcbAddress() { return nextPcbAddress; }

    /** Reemplaza el contexto guardado sólo después de validar su PC lógico. */
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

    /** El PC lógico admite EndExclusive únicamente como marcador terminal. */
    private void validateOperationalProgramCounter(int programCounter) {
        if (programCounter < logicalCodeRange.base().value() || programCounter > logicalCodeRange.endExclusive().value()) {
            throw new InvalidProcessProgramCounterException(
                    "Logical Program Counter must be between " + logicalCodeRange.base().value()
                            + " and " + logicalCodeRange.endExclusive().value() + ": " + programCounter);
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
