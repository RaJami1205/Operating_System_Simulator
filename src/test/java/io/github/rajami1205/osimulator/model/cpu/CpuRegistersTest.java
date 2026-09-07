package io.github.rajami1205.osimulator.model.cpu;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.rajami1205.osimulator.model.cpu.exception.InvalidRegisterValueException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CpuRegistersTest {

    @Test
    void shouldInitializeAllDataRegistersToZero() {
        CpuRegisters registers = new CpuRegisters();

        assertAll(
                () -> assertEquals(0, registers.accumulator()),
                () -> assertEquals(0, registers.readRegister(RegisterName.AX)),
                () -> assertEquals(0, registers.readRegister(RegisterName.BX)),
                () -> assertEquals(0, registers.readRegister(RegisterName.CX)),
                () -> assertEquals(0, registers.readRegister(RegisterName.DX))
        );
    }

    @ParameterizedTest
    @CsvSource({
            "AX, 10",
            "BX, 20",
            "CX, 30",
            "DX, 40"
    })
    void shouldReadAndWriteEachGeneralRegister(RegisterName register, int value) {
        CpuRegisters registers = new CpuRegisters();

        registers.writeRegister(register, value);

        assertEquals(value, registers.readRegister(register));
    }

    @ParameterizedTest
    @ValueSource(ints = {-127, 127})
    void shouldAcceptBoundaryValuesForGeneralRegisters(int value) {
        CpuRegisters registers = new CpuRegisters();

        registers.writeRegister(RegisterName.AX, value);

        assertEquals(value, registers.readRegister(RegisterName.AX));
    }

    @ParameterizedTest
    @ValueSource(ints = {-128, 128})
    void shouldRejectValuesOutsideGeneralRegisterRange(int value) {
        CpuRegisters registers = new CpuRegisters();

        InvalidRegisterValueException exception = assertThrows(
                InvalidRegisterValueException.class,
                () -> registers.writeRegister(RegisterName.AX, value)
        );

        assertNotNull(exception.getMessage());
        assertFalse(exception.getMessage().isBlank());
    }

    @Test
    void shouldOverwriteGeneralRegisterValue() {
        CpuRegisters registers = new CpuRegisters();
        registers.writeRegister(RegisterName.AX, 10);

        registers.writeRegister(RegisterName.AX, 20);

        assertEquals(20, registers.readRegister(RegisterName.AX));
    }

    @ParameterizedTest
    @ValueSource(ints = {-128, 128})
    void shouldPreserveGeneralRegisterAfterFailedWrite(int invalidValue) {
        CpuRegisters registers = new CpuRegisters();
        registers.writeRegister(RegisterName.AX, 50);

        assertThrows(
                InvalidRegisterValueException.class,
                () -> registers.writeRegister(RegisterName.AX, invalidValue)
        );

        assertEquals(50, registers.readRegister(RegisterName.AX));
    }

    @Test
    void shouldRejectNullRegisterName() {
        CpuRegisters registers = new CpuRegisters();

        assertAll(
                () -> assertThrows(NullPointerException.class, () -> registers.readRegister(null)),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> registers.writeRegister(null, 0)
                )
        );
    }

    @Test
    void shouldWriteAndOverwriteAccumulator() {
        CpuRegisters registers = new CpuRegisters();
        registers.writeAccumulator(25);

        registers.writeAccumulator(-20);

        assertEquals(-20, registers.accumulator());
    }

    @ParameterizedTest
    @ValueSource(ints = {-127, 127})
    void shouldAcceptAccumulatorBoundaryValues(int value) {
        CpuRegisters registers = new CpuRegisters();

        registers.writeAccumulator(value);

        assertEquals(value, registers.accumulator());
    }

    @ParameterizedTest
    @ValueSource(ints = {-128, 128})
    void shouldRejectValuesOutsideAccumulatorRange(int value) {
        CpuRegisters registers = new CpuRegisters();

        InvalidRegisterValueException exception = assertThrows(
                InvalidRegisterValueException.class,
                () -> registers.writeAccumulator(value)
        );

        assertNotNull(exception.getMessage());
        assertFalse(exception.getMessage().isBlank());
    }

    @ParameterizedTest
    @ValueSource(ints = {-128, 128})
    void shouldPreserveAccumulatorAfterFailedWrite(int invalidValue) {
        CpuRegisters registers = new CpuRegisters();
        registers.writeAccumulator(-20);

        assertThrows(
                InvalidRegisterValueException.class,
                () -> registers.writeAccumulator(invalidValue)
        );

        assertEquals(-20, registers.accumulator());
    }

    @Test
    void shouldKeepDataRegistersIndependent() {
        CpuRegisters registers = new CpuRegisters();

        registers.writeRegister(RegisterName.AX, 10);
        registers.writeRegister(RegisterName.BX, 20);
        registers.writeRegister(RegisterName.CX, 30);
        registers.writeRegister(RegisterName.DX, 40);
        registers.writeAccumulator(50);

        assertAll(
                () -> assertEquals(10, registers.readRegister(RegisterName.AX)),
                () -> assertEquals(20, registers.readRegister(RegisterName.BX)),
                () -> assertEquals(30, registers.readRegister(RegisterName.CX)),
                () -> assertEquals(40, registers.readRegister(RegisterName.DX)),
                () -> assertEquals(50, registers.accumulator())
        );
    }
}
