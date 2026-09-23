package io.github.rajami1205.osimulator.model.cpu;

import io.github.rajami1205.osimulator.model.cpu.exception.InvalidProgramCounterException;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Gestiona el estado mutable y validado de los registros de la CPU simulada.
 */
public final class CpuRegisters<I> {

    private final EnumMap<RegisterName, Integer> generalRegisters =
            new EnumMap<>(RegisterName.class);
    private int accumulator;
    private int programCounter;
    private I instructionRegister;
    private int ah;
    private int al;
    private ConditionFlags conditionFlags;

    // Inicializa los registros numéricos y el IR del procesador simulado.
    public CpuRegisters() {
        restoreInitialState();
    }

    // Expone el valor lógico almacenado en AC.
    public int accumulator() {
        return accumulator;
    }

    // Valida el rango lógico antes de actualizar AC.
    public void writeAccumulator(int value) {
        CpuValueRange.validateRegisterValue(value);
        accumulator = value;
    }

    // Consulta el valor de un registro general válido.
    public int readRegister(RegisterName register) {
        return generalRegisters.get(requireRegister(register));
    }

    // Valida el registro y el rango lógico antes de escribir su valor.
    public void writeRegister(RegisterName register, int value) {
        RegisterName nonNullRegister = requireRegister(register);
        CpuValueRange.validateRegisterValue(value);
        generalRegisters.put(nonNullRegister, value);
    }

    // Expone la posición actual del contador de programa.
    public int programCounter() {
        return programCounter;
    }

    // Rechaza direcciones negativas antes de actualizar el PC de la CPU.
    public void setProgramCounter(int address) {
        if (address < 0) {
            throw new InvalidProgramCounterException(
                    "Program Counter address must not be negative: " + address
            );
        }

        programCounter = address;
    }

    // Expone la instrucción del IR, si existe.
    public Optional<I> instructionRegister() {
        return Optional.ofNullable(instructionRegister);
    }

    // Conserva en IR la instrucción recibida para ejecución.
    public void loadInstructionRegister(I instruction) {
        instructionRegister = Objects.requireNonNull(
                instruction,
                "instruction must not be null"
        );
    }

    // Descarta la instrucción actualmente almacenada en IR.
    public void clearInstructionRegister() {
        instructionRegister = null;
    }

    public int ah() {
        return ah;
    }

    public void writeAh(int value) {
        CpuValueRange.validateRegisterValue(value);
        ah = value;
    }

    public int al() {
        return al;
    }

    public void writeAl(int value) {
        CpuValueRange.validateRegisterValue(value);
        al = value;
    }

    public ConditionFlags conditionFlags() {
        return conditionFlags;
    }

    public void writeConditionFlags(ConditionFlags flags) {
        conditionFlags = Objects.requireNonNull(flags, "flags must not be null");
    }

    /** Captura todos los registros; el contenido de IR debe ser inmutable. */
    public CpuContext<I> snapshot() {
        return new CpuContext<>(programCounter, instructionRegister(), accumulator,
                readRegister(RegisterName.AX), readRegister(RegisterName.BX),
                readRegister(RegisterName.CX), readRegister(RegisterName.DX), ah, al, conditionFlags);
    }

    /** Restaura un contexto ya validado sin compartir el mapa mutable de registros. */
    public void restore(CpuContext<I> context) {
        Objects.requireNonNull(context, "context must not be null");
        programCounter = context.programCounter();
        instructionRegister = context.instructionRegister().orElse(null);
        accumulator = context.accumulator();
        generalRegisters.put(RegisterName.AX, context.ax());
        generalRegisters.put(RegisterName.BX, context.bx());
        generalRegisters.put(RegisterName.CX, context.cx());
        generalRegisters.put(RegisterName.DX, context.dx());
        ah = context.ah();
        al = context.al();
        conditionFlags = context.conditionFlags();
    }

    // Restablece los registros numéricos y vacía el IR.
    public void reset() {
        restoreInitialState();
    }

    // Lleva los registros numéricos a cero y deja el IR vacío.
    private void restoreInitialState() {
        accumulator = 0;
        ah = 0;
        al = 0;
        conditionFlags = ConditionFlags.CLEAR;

        for (RegisterName register : RegisterName.values()) {
            generalRegisters.put(register, 0);
        }

        programCounter = 0;
        instructionRegister = null;
    }

    // Rechaza referencias nulas a registros generales.
    private RegisterName requireRegister(RegisterName register) {
        return Objects.requireNonNull(register, "register must not be null");
    }

}
