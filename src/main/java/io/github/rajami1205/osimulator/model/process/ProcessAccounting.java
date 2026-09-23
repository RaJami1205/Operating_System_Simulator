package io.github.rajami1205.osimulator.model.process;

import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Datos contables inmutables; no consultan un reloj ni avanzan automáticamente. */
public record ProcessAccounting(OptionalInt cpuId, OptionalLong startTime,
                                long cpuTime, OptionalLong finishTime) {
    public ProcessAccounting {
        Objects.requireNonNull(cpuId, "cpuId must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(finishTime, "finishTime must not be null");
        if (cpuTime < 0 || (startTime.isPresent() && startTime.getAsLong() < 0)
                || (finishTime.isPresent() && finishTime.getAsLong() < 0)) {
            throw new IllegalArgumentException("Accounting times must not be negative");
        }
        if (startTime.isPresent() && finishTime.isPresent()
                && finishTime.getAsLong() < startTime.getAsLong()) {
            throw new IllegalArgumentException("Finish time must not precede start time");
        }
    }

    public static ProcessAccounting initial() {
        return new ProcessAccounting(OptionalInt.empty(), OptionalLong.empty(), 0, OptionalLong.empty());
    }
}
