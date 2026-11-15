package io.github.rajami1205.osimulator.model.configuration;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Parámetros de la política Lottery para toda la sesión.
 *
 * @param seed semilla del sorteo (1..999999); vacía significa "aleatoria" y la sesión la resuelve una vez al
 *             inicializar, para que la corrida pueda reproducirse con la semilla mostrada
 */
public record LotteryConfiguration(OptionalLong seed) {
    /** Rango corto y positivo: fácil de leer, anotar y volver a escribir para repetir una corrida. */
    public static final long MIN_SEED = 1;
    public static final long MAX_SEED = 999_999;

    public LotteryConfiguration {
        Objects.requireNonNull(seed, "seed must not be null");
        if (seed.isPresent() && (seed.getAsLong() < MIN_SEED || seed.getAsLong() > MAX_SEED)) {
            throw new IllegalArgumentException("Seed must be between " + MIN_SEED + " and " + MAX_SEED);
        }
    }

    /** Semilla aleatoria (se resuelve al inicializar la sesión). */
    public static LotteryConfiguration defaults() {
        return new LotteryConfiguration(OptionalLong.empty());
    }

    public static LotteryConfiguration withSeed(long seed) {
        return new LotteryConfiguration(OptionalLong.of(seed));
    }
}
