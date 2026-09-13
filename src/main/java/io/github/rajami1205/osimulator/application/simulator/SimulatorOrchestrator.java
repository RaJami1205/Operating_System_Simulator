package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorLifecycle;
import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot.CpuSnapshot;
import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot.InstructionSnapshot;
import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot.MemoryEntry;
import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot.ProcessSnapshot;
import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot.ProgramEntry;
import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.instruction.AddInstruction;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.MovInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import io.github.rajami1205.osimulator.model.instruction.binary.InstructionBinaryCodec;
import io.github.rajami1205.osimulator.model.memory.Memory;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Coordina una sesión de un proceso y mantiene privados los objetos mutables del modelo. */
public final class SimulatorOrchestrator {
    private final ProgramLoader programLoader;
    private final ExecutionEngine executionEngine;
    private final InstructionBinaryCodec binaryCodec;
    private final SimulatorLifecycle lifecycle = new SimulatorLifecycle();
    private Memory<Instruction> memory;
    private CpuRegisters<Instruction> cpu;
    private ProcessControlBlock pcb;

    // Recibe los servicios que coordinan la carga, ejecución y representación binaria.
    public SimulatorOrchestrator(
            ProgramLoader programLoader,
            ExecutionEngine executionEngine,
            InstructionBinaryCodec binaryCodec
    ) {
        this.programLoader = Objects.requireNonNull(programLoader, "programLoader must not be null");
        this.executionEngine = Objects.requireNonNull(executionEngine, "executionEngine must not be null");
        this.binaryCodec = Objects.requireNonNull(binaryCodec, "binaryCodec must not be null");
    }

    // Crea Memory y CPU válidas antes de publicar la sesión inicializada.
    public void initialize(int totalPositions, int kernelReservedPositions) {
        requireState("initialize", SimulatorState.CONFIGURING);
        MemoryConfiguration configuration = new MemoryConfiguration(totalPositions, kernelReservedPositions);
        Memory<Instruction> newMemory = new Memory<>(configuration);
        CpuRegisters<Instruction> newCpu = new CpuRegisters<>();
        lifecycle.initialize();
        memory = newMemory;
        cpu = newCpu;
    }

    // Carga el programa como único proceso antes de marcarlo disponible para ejecución.
    public void loadProgram(List<Instruction> instructions) {
        requireState("loadProgram", SimulatorState.INITIALIZED);
        List<Instruction> program = List.copyOf(
                Objects.requireNonNull(instructions, "instructions must not be null"));
        pcb = programLoader.load(memory, 1, program);
        lifecycle.markProgramLoaded();
    }

    // Inicia la ejecución lógica sin ejecutar instrucciones.
    public void start() {
        lifecycle.startExecution();
    }

    // Ejecuta una instrucción y propaga al lifecycle la finalización o el fallo.
    public void step() {
        requireState("step", SimulatorState.RUNNING);
        if (memory == null || cpu == null || pcb == null) {
            throw new IllegalStateException("Step requires active Memory, CPU and PCB");
        }
        try {
            executionEngine.executeNext(memory, cpu, pcb);
        } catch (ExecutionEngineException exception) {
            lifecycle.markError();
            throw exception;
        }
        if (pcb.state() == ProcessState.TERMINATED) {
            lifecycle.finishExecution();
        }
    }

    // Pausa la sesión sin modificar el estado del proceso.
    public void pause() {
        lifecycle.pauseExecution();
    }

    // Devuelve la sesión pausada al estado RUNNING.
    public void resume() {
        lifecycle.resumeExecution();
    }

    // Descarta Memory, CPU y PCB y devuelve la sesión a CONFIGURING.
    public void reset() {
        lifecycle.reset();
        memory = null;
        cpu = null;
        pcb = null;
    }

    // Construye una vista inmutable de la CPU, el proceso y la memoria de la sesión.
    public SimulatorSnapshot snapshot() {
        Optional<CpuSnapshot> cpuSnapshot = Optional.empty();
        Optional<InstructionSnapshot> currentInstruction = Optional.empty();
        if (cpu != null) {
            cpuSnapshot = Optional.of(new CpuSnapshot(
                    cpu.programCounter(), cpu.accumulator(),
                    cpu.readRegister(RegisterName.AX), cpu.readRegister(RegisterName.BX),
                    cpu.readRegister(RegisterName.CX), cpu.readRegister(RegisterName.DX),
                    cpu.instructionRegister().map(this::semanticText)));
            currentInstruction = cpu.instructionRegister().map(this::instructionSnapshot);
        }
        Optional<ProcessSnapshot> process = Optional.empty();
        List<ProgramEntry> program = new ArrayList<>();
        if (pcb != null) {
            process = Optional.of(new ProcessSnapshot(
                    pcb.processId(), pcb.state().name(), pcb.programStartAddress(),
                    pcb.instructionCount(), pcb.programEndAddressExclusive(), pcb.programCounter()));
            for (int address = pcb.programStartAddress(); address < pcb.programEndAddressExclusive(); address++) {
                program.add(new ProgramEntry(address, semanticText(memory.read(address).orElseThrow())));
            }
        }
        List<MemoryEntry> memoryEntries = new ArrayList<>();
        if (memory != null) {
            for (int address = 0; address < memory.size(); address++) {
                memoryEntries.add(new MemoryEntry(address, memory.regionOf(address).name(),
                        memory.read(address).map(this::semanticText)));
            }
        }
        return new SimulatorSnapshot(lifecycle.state(), cpuSnapshot, currentInstruction,
                process, program, memoryEntries);
    }

    // Obtiene la representación semántica y binaria de una instrucción.
    private InstructionSnapshot instructionSnapshot(Instruction instruction) {
        var words = binaryCodec.encode(instruction).words();
        return new InstructionSnapshot(semanticText(instruction), instruction.opcode().name(),
                operandText(instruction), words.get(0).bits(),
                words.size() == 2 ? Optional.of(words.get(1).bits()) : Optional.empty());
    }

    // Compone el texto de una instrucción para su visualización.
    private String semanticText(Instruction instruction) {
        return instruction.opcode().name() + " " + operandText(instruction);
    }

    // Describe los operandos según el tipo de instrucción.
    private String operandText(Instruction instruction) {
        return switch (instruction) {
            case MovInstruction mov -> mov.destination().name() + ", " + mov.immediate();
            case LoadInstruction load -> load.source().name();
            case StoreInstruction store -> store.destination().name();
            case AddInstruction add -> add.source().name();
            case SubInstruction sub -> sub.source().name();
        };
    }

    // Rechaza operaciones que no corresponden al estado actual de la sesión.
    private void requireState(String operation, SimulatorState expected) {
        if (lifecycle.state() != expected) {
            throw new IllegalStateException("Cannot " + operation + " while simulator state is " + lifecycle.state());
        }
    }
}
