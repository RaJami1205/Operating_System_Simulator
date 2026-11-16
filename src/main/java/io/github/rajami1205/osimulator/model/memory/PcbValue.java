package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.cpu.RegisterValue;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Valores capturados, tipados e inmutables; Empty no libera la celda reservada. */
public sealed interface PcbValue {
    enum Empty implements PcbValue { INSTANCE }
    record Number(long value) implements PcbValue {}
    record Flag(boolean value) implements PcbValue {}
    record State(ProcessState value) implements PcbValue {
        public State { Objects.requireNonNull(value); }
    }
    record InstructionValue(Instruction value) implements PcbValue {
        public InstructionValue { Objects.requireNonNull(value); }
    }
    record Register(RegisterValue value) implements PcbValue {
        public Register { Objects.requireNonNull(value); }
    }
    record Timestamp(Instant value) implements PcbValue {
        public Timestamp { Objects.requireNonNull(value); }
    }
    record Names(List<String> values) implements PcbValue {
        public Names { values = List.copyOf(values); }
    }
}
