package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
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
        List<MemoryEntry> memory
) {
    // Valida el snapshot y copia las listas para evitar cambios externos.
    public SimulatorSnapshot {
        Objects.requireNonNull(simulatorState, "simulatorState must not be null");
        Objects.requireNonNull(cpu, "cpu must not be null");
        Objects.requireNonNull(currentInstruction, "currentInstruction must not be null");
        Objects.requireNonNull(process, "process must not be null");
        program = List.copyOf(program);
        memory = List.copyOf(memory);
    }
    public record CpuSnapshot(
            int programCounter, int accumulator, int ax, int bx, int cx, int dx,
            Optional<String> instructionRegister
    ) {
        // Valida la representación opcional del IR en el snapshot de CPU.
        public CpuSnapshot {
            Objects.requireNonNull(instructionRegister, "instructionRegister must not be null");
        }
    }
    public record InstructionSnapshot(
            String semanticInstruction, String opcode, String operand,
            String word1, Optional<String> word2
    ) {
        // Valida los textos y la segunda palabra opcional de la instrucción.
        public InstructionSnapshot {
            Objects.requireNonNull(semanticInstruction, "semanticInstruction must not be null");
            Objects.requireNonNull(opcode, "opcode must not be null");
            Objects.requireNonNull(operand, "operand must not be null");
            Objects.requireNonNull(word1, "word1 must not be null");
            Objects.requireNonNull(word2, "word2 must not be null");
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
}
