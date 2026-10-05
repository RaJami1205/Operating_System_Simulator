package io.github.rajami1205.osimulator.model.cpu;

import io.github.rajami1205.osimulator.model.cpu.exception.InvalidRegisterValueException;

/** Rango lógico de datos de CPU; no limita direcciones del PC. */
public final class CpuValueRange {
    public static final int MIN_VALUE = -32768;
    public static final int MAX_VALUE = 32767;

    /** Impide instanciar la definición compartida de límites de datos. */
    private CpuValueRange() {
    }

    /** Indica si el dato cabe en el intervalo inclusivo -32768..32767; no valida direcciones de PC. */
    public static boolean contains(int value) {
        return value >= MIN_VALUE && value <= MAX_VALUE;
    }

    /** Rechaza con InvalidRegisterValueException cualquier dato fuera del rango compartido. */
    public static void validateRegisterValue(int value) {
        if (!contains(value)) {
            throw new InvalidRegisterValueException(
                    "Register value must be between " + MIN_VALUE + " and " + MAX_VALUE + ": " + value);
        }
    }
}
