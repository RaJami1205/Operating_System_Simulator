package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.cpu.CpuContext;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.process.ProcessAccounting;
import java.util.Objects;

/** Historial inmutable de contexto final y accounting; no conserva PCB ni handles de recursos activos. */
public record CompletedProcessRecord(int processId, CpuContext<Instruction> finalContext, ProcessAccounting accounting) {
    /** Exige PID positivo, contexto final y accounting no nulos, sin retener recursos mutables. */
    public CompletedProcessRecord {
        if (processId <= 0) throw new IllegalArgumentException("PID must be positive");
        Objects.requireNonNull(finalContext);
        Objects.requireNonNull(accounting);
    }
}
