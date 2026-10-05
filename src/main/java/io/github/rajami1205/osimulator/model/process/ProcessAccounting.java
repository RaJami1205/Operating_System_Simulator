package io.github.rajami1205.osimulator.model.process;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Timestamps reales inmutables de inicio/fin y contador independiente de CPU ticks exitosos. El elapsed
 * derivado incluye bloqueos, pausas y demoras; no se almacena por duplicado.
 */
public record ProcessAccounting(OptionalInt cpuId, Optional<Instant> startTime,
                                long cpuTicks, Optional<Instant> finishTime) {
    /** Exige optionals no nulos, ticks no negativos y finish no anterior a start cuando ambos existen. */
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

    /**
     * Prepara CPU 0, inicio del primer intento y un tick adicional; rechaza overflow y ticks tras finish.
     * El runtime publica el record sólo si el engine retorna exitosamente.
     */
    public ProcessAccounting recordSuccessfulCpuTick(int executingCpuId, Instant tickStart) {
        Objects.requireNonNull(tickStart, "tickStart must not be null");
        if (executingCpuId != 0 || (cpuId.isPresent() && cpuId.getAsInt() != 0)) {
            throw new IllegalArgumentException("Only canonical CPU 0 is supported");
        }
        if (finishTime.isPresent()) throw new IllegalStateException("Completed accounting cannot consume CPU");
        return new ProcessAccounting(OptionalInt.of(0), startTime.isPresent() ? startTime : Optional.of(tickStart),
                Math.incrementExact(cpuTicks), Optional.empty());
    }

    /** Registra una única finalización real; rechaza un segundo finish o un instante anterior al inicio. */
    public ProcessAccounting finishAt(Instant instant) {
        if (finishTime.isPresent()) throw new IllegalStateException("Finish already recorded");
        return new ProcessAccounting(cpuId, startTime, cpuTicks, Optional.of(instant));
    }

    /**
     * Devuelve Duration entre start y finish si ambos existen, incluyendo bloqueo, suspensión, Pause y
     * demoras manuales.
     */
    public Optional<Duration> elapsedTime() {
        return startTime.flatMap(start -> finishTime.map(finish -> Duration.between(start, finish)));
    }

    /** Crea accounting sin CPU ni timestamps asignados y con cero ticks consumidos. */
    public static ProcessAccounting initial() {
        return new ProcessAccounting(OptionalInt.empty(), Optional.empty(), 0, Optional.empty());
    }
}
