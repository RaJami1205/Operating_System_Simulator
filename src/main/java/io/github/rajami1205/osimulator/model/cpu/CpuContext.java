package io.github.rajami1205.osimulator.model.cpu;

import io.github.rajami1205.osimulator.model.cpu.exception.InvalidProgramCounterException;
import java.util.Objects;
import java.util.Optional;

/**
 * Copia inmutable del estado restaurable de CPU.
 * El contenido genérico del IR debe ser inmutable: su referencia no se copia profundamente.
 */
public record CpuContext<I>(
        int programCounter, Optional<I> instructionRegister,
        int accumulator, int ax, int bx, int cx, int dx, int ah, int al,
        ConditionFlags conditionFlags
) {
    public CpuContext {
        if (programCounter < 0) {
            throw new InvalidProgramCounterException("Program Counter address must not be negative: " + programCounter);
        }
        Objects.requireNonNull(instructionRegister, "instructionRegister must not be null");
        Objects.requireNonNull(conditionFlags, "conditionFlags must not be null");
        CpuValueRange.validateRegisterValue(accumulator);
        CpuValueRange.validateRegisterValue(ax);
        CpuValueRange.validateRegisterValue(bx);
        CpuValueRange.validateRegisterValue(cx);
        CpuValueRange.validateRegisterValue(dx);
        CpuValueRange.validateRegisterValue(ah);
        CpuValueRange.validateRegisterValue(al);
    }
}
