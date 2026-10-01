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
        List<Integer> screenOutput
) {
    /** Compatibility construction for the original read views. */
    public SimulatorSnapshot(SimulatorState state, Optional<CpuSnapshot> cpu,
            Optional<InstructionSnapshot> instruction, Optional<ProcessSnapshot> process,
            List<ProgramEntry> program, List<MemoryEntry> memory) {
        this(state, cpu, instruction, process, program, memory, Optional.empty(), Optional.empty(),
                Optional.empty(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
    // Valida el snapshot y copia las listas para evitar cambios externos.
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
    }
    public record CpuSnapshot(
            int programCounter, int accumulator, int ax, int bx, int cx, RegisterValue dxValue,
            Optional<String> instructionRegister, int ah, RegisterValue alValue, ConditionFlags flags
    ) {
        public CpuSnapshot(int pc, int ac, int ax, int bx, int cx, RegisterValue dx, Optional<String> ir) {
            this(pc, ac, ax, bx, cx, dx, ir, 0, new NumericRegisterValue(0), ConditionFlags.CLEAR);
        }
        public String dxText() { return dxValue.displayText(); }
        public String alText() { return alValue.displayText(); }
        // Valida la representación opcional del IR en el snapshot de CPU.
        public CpuSnapshot(int pc, int ac, int ax, int bx, int cx, int dx, Optional<String> ir) {
            this(pc, ac, ax, bx, cx, new NumericRegisterValue(dx), ir);
        }
        public int dx() { return dxValue.numericValue(); }
        public CpuSnapshot {
            Objects.requireNonNull(alValue);
            Objects.requireNonNull(flags);
            Objects.requireNonNull(dxValue, "DX must not be null");
            Objects.requireNonNull(instructionRegister, "instructionRegister must not be null");
        }
    }
    public record InstructionSnapshot(
            String semanticInstruction, String opcode, String operand
    ) {
        // Valida los textos semánticos de la instrucción.
        public InstructionSnapshot {
            Objects.requireNonNull(semanticInstruction, "semanticInstruction must not be null");
            Objects.requireNonNull(opcode, "opcode must not be null");
            Objects.requireNonNull(operand, "operand must not be null");
        }
    }
    public record ProcessSnapshot(
            int processId, String processState, int startAddress,
            int instructionCount, int endExclusive, int savedProgramCounter
    ) {
        // Valida el estado textual del proceso representado.
        public ProcessSnapshot {
            Objects.requireNonNull(processState, "processState must not be null");
        }
    }
    public record ProgramEntry(int address, String instruction) {
        // Valida la representación de una instrucción cargada.
        public ProgramEntry {
            Objects.requireNonNull(instruction, "instruction must not be null");
        }
    }
    public record MemoryEntry(int address, String region, Optional<String> content) {
        // Valida la región y el contenido opcional de una posición de memoria.
        public MemoryEntry {
            Objects.requireNonNull(region, "region must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }
    }
    public enum Residency { RESIDENT, SUSPENDED }
    public record ProcessDetails(int processId, ProcessState state, int savedProgramCounter,
            Residency residency, Optional<Integer> base, int limit, int priority, CpuSnapshot savedContext,
            List<Integer> stack, List<String> openFiles, int kernelAddress, Optional<Integer> nextPcbAddress,
            ProcessAccounting accounting) {
        public ProcessDetails {
            Objects.requireNonNull(state); Objects.requireNonNull(residency); Objects.requireNonNull(base);
            Objects.requireNonNull(savedContext); Objects.requireNonNull(nextPcbAddress); Objects.requireNonNull(accounting);
            stack = List.copyOf(stack); openFiles = List.copyOf(openFiles);
        }
    }
    public record CompletedProcess(int processId, CpuSnapshot finalContext, ProcessAccounting accounting) {
        public CompletedProcess { Objects.requireNonNull(finalContext); Objects.requireNonNull(accounting); }
    }
    public record StorageEntry(int address, StorageRegion region, String content) {
        public StorageEntry { Objects.requireNonNull(region); Objects.requireNonNull(content); }
    }
}
