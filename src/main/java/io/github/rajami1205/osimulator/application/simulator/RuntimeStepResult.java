package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.model.execution.TickResult;
import java.util.Objects;
import java.util.List;
import java.util.Optional;
import io.github.rajami1205.osimulator.model.cpu.CpuId;

/**
 * Resultado de un intervalo global. Las variantes históricas Executed/Idle se conservan para CPUCount=1.
 */
public sealed interface RuntimeStepResult {
    /** Expone la disponibilidad global posterior al Step, tanto para Executed como para Idle. */
    RuntimeStatus status();
    /** Resultados locales; Executed e Idle legacy representan exclusivamente CPU0. */
    default List<CpuResult> cpuResults() {
        return switch (this) {
            case Executed value -> List.of(CpuResult.executed(CpuId.ZERO, value.processId(), value.tickResult()));
            case Idle ignored -> List.of(CpuResult.unexecuted(CpuId.ZERO, Optional.empty()));
            case Global value -> value.cpuResults();
        };
    }
    enum CpuOutcome { EXECUTED, IDLE, NOT_EXECUTED, FAILED }
    record CpuResult(CpuId cpuId, Optional<Integer> processId, CpuOutcome outcome, Optional<TickResult> tickResult) {
        public CpuResult {
            Objects.requireNonNull(cpuId); Objects.requireNonNull(processId);
            Objects.requireNonNull(outcome); Objects.requireNonNull(tickResult);
            if (processId.filter(pid -> pid <= 0).isPresent()
                    || (outcome == CpuOutcome.IDLE) != processId.isEmpty()
                    || (outcome == CpuOutcome.EXECUTED) != tickResult.isPresent()) {
                throw new IllegalArgumentException("Inconsistent CPU result");
            }
        }
        public static CpuResult executed(CpuId id, int pid, TickResult result) {
            return new CpuResult(id, Optional.of(pid), CpuOutcome.EXECUTED, Optional.of(result));
        }
        public static CpuResult unexecuted(CpuId id, Optional<Integer> pid) {
            return new CpuResult(id, pid, pid.isEmpty() ? CpuOutcome.IDLE : CpuOutcome.NOT_EXECUTED, Optional.empty());
        }
    }
    record Global(List<CpuResult> cpuResults, RuntimeStatus status) implements RuntimeStepResult {
        public Global {
            cpuResults = List.copyOf(cpuResults);
            Objects.requireNonNull(status);
            if (cpuResults.isEmpty() || cpuResults.size() > CpuId.MAX_CPU_COUNT) throw new IllegalArgumentException("Invalid CPU results");
            for (int i = 0; i < cpuResults.size(); i++) {
                if (cpuResults.get(i).cpuId().value() != i) throw new IllegalArgumentException("Results must follow CPU order");
            }
        }
    }
    /** PID que consumió un tick, resultado de la instrucción y estado global posterior. */
    record Executed(int processId, TickResult tickResult, RuntimeStatus status) implements RuntimeStepResult {
        /** Exige PID positivo y resultados no nulos para un tick ejecutado. */
        public Executed {
            if (processId <= 0) throw new IllegalArgumentException("PID must be positive");
            Objects.requireNonNull(tickResult);
            Objects.requireNonNull(status);
        }
    }
    /** Sin CPU: una espera confirma un segundo lógico; FINISHED no consume tiempo. */
    record Idle(RuntimeStatus status) implements RuntimeStepResult {
        /** Exige estado global no nulo para un Step sin consumo de CPU. */
        public Idle { Objects.requireNonNull(status); }
    }
}
