package io.github.rajami1205.osimulator.model.cpu;

/** Valor numérico inmutable del rango signed 16-bit compartido por los registros de datos. */
public record NumericRegisterValue(int value) implements RegisterValue {
    /** Rechaza números fuera del rango signed 16-bit antes de publicar el valor. */
    public NumericRegisterValue { CpuValueRange.validateRegisterValue(value); }
}
