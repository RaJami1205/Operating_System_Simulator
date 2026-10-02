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
    /**
     * Construye contexto validado: PC no negativo, datos signed 16-bit, IR opcional y valores/flags no
     * nulos; el overload numérico crea valores tipados.
     */
    public CpuContext(int programCounter, Optional<I> instructionRegister,
            int accumulator, int ax, int bx, int cx, int dx, int ah, int al, ConditionFlags flags) {
        this(programCounter, instructionRegister, accumulator, ax, bx, cx,
                new NumericRegisterValue(dx), ah, new NumericRegisterValue(al), flags);
    }
    /** Devuelve DX numérico; rechaza acceso numérico cuando el contexto conserva texto. */
    public int dx() { return dxValue.numericValue(); }
    /** Devuelve AL numérico; rechaza acceso numérico cuando el contexto conserva texto. */
    public int al() { return alValue.numericValue(); }

    /** Crea PC/datos en cero, IR vacío y flags limpias para un proceso sin ejecución. */
    public static <I> CpuContext<I> initial() {
        return new CpuContext<>(0, Optional.empty(), 0, 0, 0, 0, 0, 0, 0, ConditionFlags.CLEAR);
    }

    /** Copia el contexto conservando todos los valores salvo el PC. */
    public CpuContext<I> withProgramCounter(int programCounter) {
        return new CpuContext<>(programCounter, instructionRegister, accumulator,
                ax, bx, cx, dxValue, ah, alValue, conditionFlags);
    }

    /** Crea un contexto con DX reemplazado, sin modificar el CPU activo ni otros campos guardados. */
    public CpuContext<I> withDx(RegisterValue value) {
        return new CpuContext<>(programCounter, instructionRegister, accumulator,
                ax, bx, cx, value, ah, alValue, conditionFlags);
    }

    /**
     * Construye contexto validado: PC no negativo, datos signed 16-bit, IR opcional y valores/flags no
     * nulos; el overload numérico crea valores tipados.
     */
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
