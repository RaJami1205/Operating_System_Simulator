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
        int accumulator, int ax, int bx, int cx, RegisterValue dxValue, int ah, RegisterValue alValue,
        ConditionFlags conditionFlags
) {
    public CpuContext(int programCounter, Optional<I> instructionRegister,
            int accumulator, int ax, int bx, int cx, int dx, int ah, int al, ConditionFlags flags) {
        this(programCounter, instructionRegister, accumulator, ax, bx, cx,
                new NumericRegisterValue(dx), ah, new NumericRegisterValue(al), flags);
    }
    public int dx() { return dxValue.numericValue(); }
    public int al() { return alValue.numericValue(); }

    public static <I> CpuContext<I> initial() {
        return new CpuContext<>(0, Optional.empty(), 0, 0, 0, 0, 0, 0, 0, ConditionFlags.CLEAR);
    }

    /** Copia el contexto conservando todos los valores salvo el PC. */
    public CpuContext<I> withProgramCounter(int programCounter) {
        return new CpuContext<>(programCounter, instructionRegister, accumulator,
                ax, bx, cx, dxValue, ah, alValue, conditionFlags);
    }

    /** Replaces DX without touching another process's active CPU. */
    public CpuContext<I> withDx(RegisterValue value) {
        return new CpuContext<>(programCounter, instructionRegister, accumulator,
                ax, bx, cx, value, ah, alValue, conditionFlags);
    }

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
        Objects.requireNonNull(dxValue, "DX must not be null");
        CpuValueRange.validateRegisterValue(ah);
        Objects.requireNonNull(alValue, "AL must not be null");
    }
}
