package io.github.rajami1205.osimulator.model.cpu;

import io.github.rajami1205.osimulator.model.cpu.exception.InvalidProgramCounterException;
import io.github.rajami1205.osimulator.model.cpu.exception.InvalidRegisterValueException;
import java.util.EnumMap;
import java.util.Objects;

/**
 * Mutable and validated state of the simulated CPU data registers.
 */
public class CpuRegisters {

    private static final int MIN_DATA_VALUE = -127;
    private static final int MAX_DATA_VALUE = 127;

    private final EnumMap<RegisterName, Integer> generalRegisters =
            new EnumMap<>(RegisterName.class);
    private int accumulator;
    private int programCounter;

    public CpuRegisters() {
        for (RegisterName register : RegisterName.values()) {
            generalRegisters.put(register, 0);
        }
    }

    public int accumulator() {
        return accumulator;
    }

    public void writeAccumulator(int value) {
        validateDataValue(value);
        accumulator = value;
    }

    public int readRegister(RegisterName register) {
        return generalRegisters.get(requireRegister(register));
    }

    public void writeRegister(RegisterName register, int value) {
        RegisterName nonNullRegister = requireRegister(register);
        validateDataValue(value);
        generalRegisters.put(nonNullRegister, value);
    }

    public int programCounter() {
        return programCounter;
    }

    public void setProgramCounter(int address) {
        if (address < 0) {
            throw new InvalidProgramCounterException(
                    "Program Counter address must not be negative: " + address
            );
        }

        programCounter = address;
    }

    private RegisterName requireRegister(RegisterName register) {
        return Objects.requireNonNull(register, "register must not be null");
    }

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
