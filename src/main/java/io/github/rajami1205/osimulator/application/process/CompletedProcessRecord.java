package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.cpu.CpuContext;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.process.ProcessAccounting;
import java.util.Objects;

/** Immutable history, without retaining a PCB or any active resource handle. */
public record CompletedProcessRecord(int processId, CpuContext<Instruction> finalContext, ProcessAccounting accounting) {
    public CompletedProcessRecord {
        if (processId <= 0) throw new IllegalArgumentException("PID must be positive");
        Objects.requireNonNull(finalContext);
        Objects.requireNonNull(accounting);
    }
}
