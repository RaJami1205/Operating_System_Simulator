package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.model.execution.TickResult;
import java.util.Objects;

/**
 * Resultado de un Step: un tick ejecutado o Idle sin consumo, acompañado por la disponibilidad global del
 * runtime.
 */
public sealed interface RuntimeStepResult {
    /** Expone la disponibilidad global posterior al Step, tanto para Executed como para Idle. */
    RuntimeStatus status();
    /** PID que consumió un tick, resultado de la instrucción y estado global posterior. */
    record Executed(int processId, TickResult tickResult, RuntimeStatus status) implements RuntimeStepResult {
        /** Exige PID positivo y resultados no nulos para un tick ejecutado. */
        public Executed {
            if (processId <= 0) throw new IllegalArgumentException("PID must be positive");
            Objects.requireNonNull(tickResult);
            Objects.requireNonNull(status);
        }
    }
    /** Step sin ejecución de CPU; informa si el workload espera o terminó. */
    record Idle(RuntimeStatus status) implements RuntimeStepResult {
        /** Exige estado global no nulo para un Step sin consumo de CPU. */
        public Idle { Objects.requireNonNull(status); }
    }
}
