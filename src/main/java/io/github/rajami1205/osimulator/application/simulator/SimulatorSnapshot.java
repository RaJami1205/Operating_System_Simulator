package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.model.cpu.RegisterValue;
import io.github.rajami1205.osimulator.model.cpu.NumericRegisterValue;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.cpu.ConditionFlags;
import io.github.rajami1205.osimulator.model.job.Job;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.ProcessAccounting;
import io.github.rajami1205.osimulator.model.storage.StorageRegion;
import io.github.rajami1205.osimulator.application.process.PendingKeyboardRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Representa valores inmutables para mostrar una sesión sin exponer su modelo. */
public record SimulatorSnapshot(
        SimulatorState simulatorState,
        Optional<CpuSnapshot> cpu,
        Optional<InstructionSnapshot> currentInstruction,
        Optional<ProcessSnapshot> process,
        List<ProgramEntry> program,
        List<MemoryEntry> memory,
        Optional<RuntimeStatus> runtimeStatus,
        Optional<Integer> ownerPid,
        Optional<SimulatorConfiguration> configuration,
        List<Job> jobs,
        List<ProcessDetails> processes,
        List<Integer> readyQueue,
        List<Integer> suspendedReadyQueue,
        List<PendingKeyboardRequest> pendingKeyboardRequests,
        List<CompletedProcess> completedProcesses,
        List<StorageEntry> storage,
        List<Integer> screenOutput,
        OptionalLong cpuTicks
) {
    /** Conserva la construcción de las vistas originales y deja vacías las vistas adicionales de la sesión. */
    public SimulatorSnapshot(SimulatorState state, Optional<CpuSnapshot> cpu,
            Optional<InstructionSnapshot> instruction, Optional<ProcessSnapshot> process,
            List<ProgramEntry> program, List<MemoryEntry> memory) {
        this(state, cpu, instruction, process, program, memory, Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), OptionalLong.empty());
    }
    /** Exige referencias no nulas, copia las colecciones y rechaza un contador de ticks negativo. */
    public SimulatorSnapshot {
        Objects.requireNonNull(simulatorState, "simulatorState must not be null");
        Objects.requireNonNull(cpu, "cpu must not be null");
        Objects.requireNonNull(currentInstruction, "currentInstruction must not be null");
        Objects.requireNonNull(process, "process must not be null");
        program = List.copyOf(program);
        memory = List.copyOf(memory);
        Objects.requireNonNull(runtimeStatus);
        Objects.requireNonNull(ownerPid);
        Objects.requireNonNull(configuration);
        jobs = List.copyOf(jobs);
        processes = List.copyOf(processes);
        readyQueue = List.copyOf(readyQueue);
        suspendedReadyQueue = List.copyOf(suspendedReadyQueue);
        pendingKeyboardRequests = List.copyOf(pendingKeyboardRequests);
        completedProcesses = List.copyOf(completedProcesses);
        storage = List.copyOf(storage);
        screenOutput = List.copyOf(screenOutput);
        Objects.requireNonNull(cpuTicks);
        if (cpuTicks.isPresent() && cpuTicks.getAsLong() < 0) throw new IllegalArgumentException("Negative CPU ticks");
    }
    /**
     * Valores inmutables de CPU y representación semántica de IR; DX/AL conservan su tipo numérico o
     * textual.
     */
    public record CpuSnapshot(
            int programCounter, int accumulator, int ax, int bx, int cx, RegisterValue dxValue,
            Optional<String> instructionRegister, int ah, RegisterValue alValue, ConditionFlags flags
    ) {
        /**
         * Construye una vista de registros con valores tipados; el overload numérico conserva
         * compatibilidad y el canonical exige referencias no nulas.
         */
        public CpuSnapshot(int pc, int ac, int ax, int bx, int cx, RegisterValue dx, Optional<String> ir) {
            this(pc, ac, ax, bx, cx, dx, ir, 0, new NumericRegisterValue(0), ConditionFlags.CLEAR);
        }
        /** Representa DX para la GUI respetando su valor numérico o textual. */
        public String dxText() { return dxValue.displayText(); }
        /** Representa AL para la GUI respetando su valor numérico o textual. */
        public String alText() { return alValue.displayText(); }
        /**
         * Construye una vista de registros con valores tipados; el overload numérico conserva
         * compatibilidad y el canonical exige referencias no nulas.
         */
        public CpuSnapshot(int pc, int ac, int ax, int bx, int cx, int dx, Optional<String> ir) {
            this(pc, ac, ax, bx, cx, new NumericRegisterValue(dx), ir);
        }
        /** Acceso numérico compatible a DX; rechaza un valor textual en lugar de convertirlo. */
        public int dx() { return dxValue.numericValue(); }
        /**
         * Construye una vista de registros con valores tipados; el overload numérico conserva
         * compatibilidad y el canonical exige referencias no nulas.
         */
        public CpuSnapshot {
            Objects.requireNonNull(alValue);
            Objects.requireNonNull(flags);
            Objects.requireNonNull(dxValue, "DX must not be null");
            Objects.requireNonNull(instructionRegister, "instructionRegister must not be null");
        }
    }
    /** Texto semántico, opcode y operandos de Current Instruction, sin representación binaria. */
    public record InstructionSnapshot(
            String semanticInstruction, String opcode, String operand
    ) {
        /** Exige textos no nulos para Current Instruction, opcode y operandos. */
        public InstructionSnapshot {
            Objects.requireNonNull(semanticInstruction, "semanticInstruction must not be null");
            Objects.requireNonNull(opcode, "opcode must not be null");
            Objects.requireNonNull(operand, "operand must not be null");
        }
    }
    /** Vista compatible de identidad, estado, bounds físicos y PC lógico guardado. */
    public record ProcessSnapshot(
            int processId, String processState, int startAddress,
            int instructionCount, int endExclusive, int savedProgramCounter
    ) {
        /** Exige estado textual no nulo para la vista compatible del proceso. */
        public ProcessSnapshot {
            Objects.requireNonNull(processState, "processState must not be null");
        }
    }
    /** Fila de programa activo con dirección física y texto de instrucción. */
    public record ProgramEntry(int address, String instruction) {
        /** Exige representación textual no nula para la instrucción de la fila. */
        public ProgramEntry {
            Objects.requireNonNull(instruction, "instruction must not be null");
        }
    }
    /** Fila de memoria con dirección, región y contenido descriptivo opcional, sin PCB mutable. */
    public record MemoryEntry(int address, String region, Optional<String> content) {
        /** Exige región y contenido opcional no nulos para la fila de memoria. */
        public MemoryEntry {
            Objects.requireNonNull(region, "region must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }
    }
    /** Ubicación observable de la imagen: USER residente o VIRTUAL_MEMORY suspended. */
    public enum Residency { RESIDENT, SUSPENDED }
    /**
     * Detalle inmutable del PCB; Base está ausente al estar suspended y las colecciones son copias, no
     * ownership del runtime.
     */
    public record ProcessDetails(int processId, ProcessState state, int savedProgramCounter,
            Residency residency, Optional<Integer> base, int limit, int priority, CpuSnapshot savedContext,
            List<Integer> stack, List<String> openFiles, int kernelAddress, Optional<Integer> nextPcbAddress,
            ProcessAccounting accounting) {
        /** Exige referencias no nulas y copia stack/open files para aislar la vista del PCB mutable. */
        public ProcessDetails {
            Objects.requireNonNull(state); Objects.requireNonNull(residency); Objects.requireNonNull(base);
            Objects.requireNonNull(savedContext); Objects.requireNonNull(nextPcbAddress); Objects.requireNonNull(accounting);
            stack = List.copyOf(stack); openFiles = List.copyOf(openFiles);
        }
    }
    /** Contexto final y accounting de un proceso retirado, sin recursos activos. */
    public record CompletedProcess(int processId, CpuSnapshot finalContext, ProcessAccounting accounting) {
        /** Exige contexto y accounting no nulos para la vista histórica. */
        public CompletedProcess { Objects.requireNonNull(finalContext); Objects.requireNonNull(accounting); }
    }
    /** Fila de SecondaryStorage con región, dirección y texto descriptivo seguro. */
    public record StorageEntry(int address, StorageRegion region, String content) {
        /** Exige región y descripción no nulas para una fila del storage. */
        public StorageEntry { Objects.requireNonNull(region); Objects.requireNonNull(content); }
    }
}
