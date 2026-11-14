package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.configuration.PriorityConfiguration;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.ProcessTable;
import java.util.Objects;

/** Construye un scheduler nuevo sobre los recursos canónicos de una CPU. */
public final class ProcessSchedulerFactory {
    private ProcessSchedulerFactory() { }

    public static ProcessScheduler create(SchedulingPolicyId policy, CpuId cpuId,
            ReadyQueue ready, ProcessTable processes) {
        return create(policy, cpuId, ready, processes, SimulatorConfiguration.DEFAULT_QUANTUM);
    }

    public static ProcessScheduler create(SchedulingPolicyId policy, CpuId cpuId,
            ReadyQueue ready, ProcessTable processes, int quantum) {
        return create(policy, cpuId, ready, processes, quantum, PriorityConfiguration.defaults());
    }

    public static ProcessScheduler create(SchedulingPolicyId policy, CpuId cpuId,
            ReadyQueue ready, ProcessTable processes, int quantum, PriorityConfiguration priority) {
        return switch (Objects.requireNonNull(policy, "policy must not be null")) {
            case FCFS -> new FcfsProcessScheduler(cpuId, ready, processes);
            case SPN -> new SpnProcessScheduler(cpuId, ready, processes);
            case SRT -> new SrtProcessScheduler(cpuId, ready, processes);
            case HRRN -> new HrrnProcessScheduler(cpuId, ready, processes);
            case PRIORITY -> new PriorityProcessScheduler(cpuId, ready, processes, priority);
            case RR -> new RoundRobinProcessScheduler(cpuId, ready, processes, quantum);
        };
    }
}
