package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.model.execution.TickResult;
import java.util.Objects;

/** An executed CPU tick or a zero-tick idle result, plus global runtime status. */
public sealed interface RuntimeStepResult {
    RuntimeStatus status();
    record Executed(int processId, TickResult tickResult, RuntimeStatus status) implements RuntimeStepResult {
        public Executed {
            if (processId <= 0) throw new IllegalArgumentException("PID must be positive");
            Objects.requireNonNull(tickResult);
            Objects.requireNonNull(status);
        }
    }
    record Idle(RuntimeStatus status) implements RuntimeStepResult {
        public Idle { Objects.requireNonNull(status); }
    }
}
