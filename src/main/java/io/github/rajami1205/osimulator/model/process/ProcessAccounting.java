package io.github.rajami1205.osimulator.model.process;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Immutable wall-clock timestamps and independent successful CPU tick count. */
public record ProcessAccounting(OptionalInt cpuId, Optional<Instant> startTime,
                                long cpuTicks, Optional<Instant> finishTime) {
    public ProcessAccounting {
        Objects.requireNonNull(cpuId, "cpuId must not be null");
        Objects.requireNonNull(startTime, "startTime must not be null");
        Objects.requireNonNull(finishTime, "finishTime must not be null");
        if (cpuTicks < 0) throw new IllegalArgumentException("CPU ticks must not be negative");
        if (startTime.isPresent() && finishTime.isPresent()
                && finishTime.orElseThrow().isBefore(startTime.orElseThrow())) {
            throw new IllegalArgumentException("Finish time must not precede start time");
        }
    }

    /** Build before execution; publish only after the attempted tick succeeds. */
    public ProcessAccounting recordSuccessfulCpuTick(int executingCpuId, Instant tickStart) {
        Objects.requireNonNull(tickStart, "tickStart must not be null");
        if (executingCpuId != 0 || (cpuId.isPresent() && cpuId.getAsInt() != 0)) {
            throw new IllegalArgumentException("Only canonical CPU 0 is supported");
        }
        if (finishTime.isPresent()) throw new IllegalStateException("Completed accounting cannot consume CPU");
        return new ProcessAccounting(OptionalInt.of(0), startTime.isPresent() ? startTime : Optional.of(tickStart),
                Math.incrementExact(cpuTicks), Optional.empty());
    }

    /** Completion is recorded once, after cleanup prevalidation. */
    public ProcessAccounting finishAt(Instant instant) {
        if (finishTime.isPresent()) throw new IllegalStateException("Finish already recorded");
        return new ProcessAccounting(cpuId, startTime, cpuTicks, Optional.of(instant));
    }

    /** Includes waiting, suspension, pauses and manual delays after first execution. */
    public Optional<Duration> elapsedTime() {
        return startTime.flatMap(start -> finishTime.map(finish -> Duration.between(start, finish)));
    }

    public static ProcessAccounting initial() {
        return new ProcessAccounting(OptionalInt.empty(), Optional.empty(), 0, Optional.empty());
    }
}
