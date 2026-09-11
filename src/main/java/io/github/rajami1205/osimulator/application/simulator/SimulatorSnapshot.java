package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable values for rendering a simulator session without exposing its model. */
public record SimulatorSnapshot(
        SimulatorState simulatorState,
        Optional<CpuSnapshot> cpu,
        Optional<InstructionSnapshot> currentInstruction,
        Optional<ProcessSnapshot> process,
        List<ProgramEntry> program,
        List<MemoryEntry> memory
) {
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
        public CpuSnapshot {
            Objects.requireNonNull(instructionRegister, "instructionRegister must not be null");
        }
    }

    public record InstructionSnapshot(
            String semanticInstruction, String opcode, String operand,
            String word1, Optional<String> word2
    ) {
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
        public ProcessSnapshot {
            Objects.requireNonNull(processState, "processState must not be null");
        }
    }

    public record ProgramEntry(int address, String instruction) {
        public ProgramEntry {
            Objects.requireNonNull(instruction, "instruction must not be null");
        }
    }

    public record MemoryEntry(int address, String region, Optional<String> content) {
        public MemoryEntry {
            Objects.requireNonNull(region, "region must not be null");
            Objects.requireNonNull(content, "content must not be null");
        }
    }
}
