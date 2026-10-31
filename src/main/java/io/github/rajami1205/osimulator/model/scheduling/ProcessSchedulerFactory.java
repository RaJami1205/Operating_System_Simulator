package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.Objects;

/** Construye un scheduler nuevo sobre los recursos canónicos de una CPU. */
public final class ProcessSchedulerFactory {
    private ProcessSchedulerFactory() { }

    public static ProcessScheduler create(SchedulingPolicyId policy, CpuId cpuId,
            ReadyQueue ready, ProcessTable processes) {
        return switch (Objects.requireNonNull(policy, "policy must not be null")) {
            case FCFS -> new FcfsProcessScheduler(cpuId, ready, processes);
        };
    }
}
