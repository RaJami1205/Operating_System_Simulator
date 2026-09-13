package io.github.rajami1205.osimulator.model.cpu;

import io.github.rajami1205.osimulator.model.cpu.exception.InvalidProgramCounterException;
import io.github.rajami1205.osimulator.model.cpu.exception.InvalidRegisterValueException;
import java.util.EnumMap;
import java.util.Objects;
import java.util.Optional;

/**
 * Gestiona el estado mutable y validado de los registros de la CPU simulada.
 */
public final class CpuRegisters<I> {

    private static final int MIN_DATA_VALUE = -127;
    private static final int MAX_DATA_VALUE = 127;

    private final EnumMap<RegisterName, Integer> generalRegisters =
            new EnumMap<>(RegisterName.class);
    private int accumulator;
    private int programCounter;
    private I instructionRegister;

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
        validateDataValue(value);
        accumulator = value;
    }

    // Consulta el valor de un registro general válido.
    public int readRegister(RegisterName register) {
        return generalRegisters.get(requireRegister(register));
    }

    // Valida el registro y el rango lógico antes de escribir su valor.
    public void writeRegister(RegisterName register, int value) {
        RegisterName nonNullRegister = requireRegister(register);
        validateDataValue(value);
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

    // Restablece los registros numéricos y vacía el IR.
    public void reset() {
        restoreInitialState();
    }

    // Lleva los registros numéricos a cero y deja el IR vacío.
    private void restoreInitialState() {
        accumulator = 0;

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

    // Impide almacenar datos fuera del rango lógico de -127 a 127.
    private void validateDataValue(int value) {
        if (value < MIN_DATA_VALUE || value > MAX_DATA_VALUE) {
            throw new InvalidRegisterValueException(
                    "Register value must be between "
                            + MIN_DATA_VALUE
                            + " and "
                            + MAX_DATA_VALUE
                            + ": "
                            + value
            );
        }
    }
}
