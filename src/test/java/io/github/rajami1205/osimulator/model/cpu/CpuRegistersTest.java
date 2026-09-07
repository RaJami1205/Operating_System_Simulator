package io.github.rajami1205.osimulator.model.cpu;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.rajami1205.osimulator.model.cpu.exception.InvalidProgramCounterException;
import io.github.rajami1205.osimulator.model.cpu.exception.InvalidRegisterValueException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CpuRegistersTest {

    @Test
    void shouldInitializeAllDataRegistersToZero() {
        CpuRegisters<String> registers = new CpuRegisters<>();

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
        CpuRegisters<String> registers = new CpuRegisters<>();

        registers.writeRegister(register, value);

        assertEquals(value, registers.readRegister(register));
    }

    @ParameterizedTest
    @ValueSource(ints = {-127, 127})
    void shouldAcceptBoundaryValuesForGeneralRegisters(int value) {
        CpuRegisters<String> registers = new CpuRegisters<>();

        registers.writeRegister(RegisterName.AX, value);

        assertEquals(value, registers.readRegister(RegisterName.AX));
    }

    @ParameterizedTest
    @ValueSource(ints = {-128, 128})
    void shouldRejectValuesOutsideGeneralRegisterRange(int value) {
        CpuRegisters<String> registers = new CpuRegisters<>();

        InvalidRegisterValueException exception = assertThrows(
                InvalidRegisterValueException.class,
                () -> registers.writeRegister(RegisterName.AX, value)
        );

        assertNotNull(exception.getMessage());
        assertFalse(exception.getMessage().isBlank());
    }

    @Test
    void shouldOverwriteGeneralRegisterValue() {
        CpuRegisters<String> registers = new CpuRegisters<>();
        registers.writeRegister(RegisterName.AX, 10);

        registers.writeRegister(RegisterName.AX, 20);

        assertEquals(20, registers.readRegister(RegisterName.AX));
    }

    @ParameterizedTest
    @ValueSource(ints = {-128, 128})
    void shouldPreserveGeneralRegisterAfterFailedWrite(int invalidValue) {
        CpuRegisters<String> registers = new CpuRegisters<>();
        registers.writeRegister(RegisterName.AX, 50);

        assertThrows(
                InvalidRegisterValueException.class,
                () -> registers.writeRegister(RegisterName.AX, invalidValue)
        );

        assertEquals(50, registers.readRegister(RegisterName.AX));
    }

    @Test
    void shouldRejectNullRegisterName() {
        CpuRegisters<String> registers = new CpuRegisters<>();

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
        CpuRegisters<String> registers = new CpuRegisters<>();
        registers.writeAccumulator(25);

        registers.writeAccumulator(-20);

        assertEquals(-20, registers.accumulator());
    }

    @ParameterizedTest
    @ValueSource(ints = {-127, 127})
    void shouldAcceptAccumulatorBoundaryValues(int value) {
        CpuRegisters<String> registers = new CpuRegisters<>();

        registers.writeAccumulator(value);

        assertEquals(value, registers.accumulator());
    }

    @ParameterizedTest
    @ValueSource(ints = {-128, 128})
    void shouldRejectValuesOutsideAccumulatorRange(int value) {
        CpuRegisters<String> registers = new CpuRegisters<>();

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
        CpuRegisters<String> registers = new CpuRegisters<>();
        registers.writeAccumulator(-20);

        assertThrows(
                InvalidRegisterValueException.class,
                () -> registers.writeAccumulator(invalidValue)
        );

        assertEquals(-20, registers.accumulator());
    }

    @Test
    void shouldKeepDataRegistersIndependent() {
        CpuRegisters<String> registers = new CpuRegisters<>();

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

    @Test
    void shouldInitializeProgramCounterToZero() {
        CpuRegisters<String> registers = new CpuRegisters<>();

        assertEquals(0, registers.programCounter());
    }

    @Test
    void shouldAcceptZeroProgramCounter() {
        CpuRegisters<String> registers = new CpuRegisters<>();

        registers.setProgramCounter(0);

        assertEquals(0, registers.programCounter());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 127, 128, 1000})
    void shouldAcceptPositiveProgramCounterValues(int address) {
        CpuRegisters<String> registers = new CpuRegisters<>();

        registers.setProgramCounter(address);

        assertEquals(address, registers.programCounter());
    }

    @Test
    void shouldAcceptMaximumIntegerAsProgramCounter() {
        CpuRegisters<String> registers = new CpuRegisters<>();

        registers.setProgramCounter(Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, registers.programCounter());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, Integer.MIN_VALUE})
    void shouldRejectNegativeProgramCounterValues(int address) {
        CpuRegisters<String> registers = new CpuRegisters<>();

        InvalidProgramCounterException exception = assertThrows(
                InvalidProgramCounterException.class,
                () -> registers.setProgramCounter(address)
        );

        assertNotNull(exception.getMessage());
        assertFalse(exception.getMessage().isBlank());
    }

    @Test
    void shouldPreserveProgramCounterAfterFailedWrite() {
        CpuRegisters<String> registers = new CpuRegisters<>();
        registers.setProgramCounter(100);

        assertThrows(
                InvalidProgramCounterException.class,
                () -> registers.setProgramCounter(-1)
        );

        assertEquals(100, registers.programCounter());
    }

    @Test
    void shouldKeepProgramCounterIndependentFromDataRegisters() {
        CpuRegisters<String> registers = new CpuRegisters<>();
        registers.writeRegister(RegisterName.AX, 10);
        registers.writeRegister(RegisterName.BX, 20);
        registers.writeRegister(RegisterName.CX, 30);
        registers.writeRegister(RegisterName.DX, 40);
        registers.writeAccumulator(50);

        registers.setProgramCounter(1000);

        assertAll(
                () -> assertEquals(10, registers.readRegister(RegisterName.AX)),
                () -> assertEquals(20, registers.readRegister(RegisterName.BX)),
                () -> assertEquals(30, registers.readRegister(RegisterName.CX)),
                () -> assertEquals(40, registers.readRegister(RegisterName.DX)),
                () -> assertEquals(50, registers.accumulator())
        );

        registers.writeRegister(RegisterName.AX, -10);

        assertAll(
                () -> assertEquals(-10, registers.readRegister(RegisterName.AX)),
                () -> assertEquals(1000, registers.programCounter())
        );
    }

    @Test
    void shouldInitializeInstructionRegisterEmpty() {
        CpuRegisters<String> registers = new CpuRegisters<>();

        assertEquals(Optional.empty(), registers.instructionRegister());
    }

    @Test
    void shouldLoadInstructionRegister() {
        CpuRegisters<String> registers = new CpuRegisters<>();

        registers.loadInstructionRegister("A");

        assertEquals(Optional.of("A"), registers.instructionRegister());
    }

    @Test
    void shouldReplaceInstructionRegisterContent() {
        CpuRegisters<String> registers = new CpuRegisters<>();
        registers.loadInstructionRegister("A");

        registers.loadInstructionRegister("B");

        assertEquals(Optional.of("B"), registers.instructionRegister());
    }

    @Test
    void shouldClearInstructionRegister() {
        CpuRegisters<String> registers = new CpuRegisters<>();
        registers.loadInstructionRegister("A");

        registers.clearInstructionRegister();

        assertEquals(Optional.empty(), registers.instructionRegister());
    }

    @Test
    void shouldClearAlreadyEmptyInstructionRegister() {
        CpuRegisters<String> registers = new CpuRegisters<>();

        assertDoesNotThrow(registers::clearInstructionRegister);
        assertEquals(Optional.empty(), registers.instructionRegister());
    }

    @Test
    void shouldClearInstructionRegisterIdempotently() {
        CpuRegisters<String> registers = new CpuRegisters<>();
        registers.loadInstructionRegister("A");

        registers.clearInstructionRegister();
        registers.clearInstructionRegister();

        assertEquals(Optional.empty(), registers.instructionRegister());
    }

    @Test
    void shouldRejectNullInstructionRegisterContent() {
        CpuRegisters<String> registers = new CpuRegisters<>();

        assertThrows(NullPointerException.class, () -> registers.loadInstructionRegister(null));
        assertEquals(Optional.empty(), registers.instructionRegister());
    }

    @Test
    void shouldPreserveInstructionRegisterAfterFailedReplacement() {
        CpuRegisters<String> registers = new CpuRegisters<>();
        registers.loadInstructionRegister("A");

        assertThrows(NullPointerException.class, () -> registers.loadInstructionRegister(null));

        assertEquals(Optional.of("A"), registers.instructionRegister());
    }

    @Test
    void shouldKeepInstructionRegisterIndependentFromNumericRegisters() {
        CpuRegisters<String> registers = new CpuRegisters<>();
        registers.writeAccumulator(50);
        registers.writeRegister(RegisterName.AX, 10);
        registers.writeRegister(RegisterName.BX, 20);
        registers.writeRegister(RegisterName.CX, 30);
        registers.writeRegister(RegisterName.DX, 40);
        registers.setProgramCounter(1000);

        registers.loadInstructionRegister("A");
        registers.loadInstructionRegister("B");
        registers.clearInstructionRegister();

        assertAll(
                () -> assertEquals(50, registers.accumulator()),
                () -> assertEquals(10, registers.readRegister(RegisterName.AX)),
                () -> assertEquals(20, registers.readRegister(RegisterName.BX)),
                () -> assertEquals(30, registers.readRegister(RegisterName.CX)),
                () -> assertEquals(40, registers.readRegister(RegisterName.DX)),
                () -> assertEquals(1000, registers.programCounter())
        );

        registers.loadInstructionRegister("instruction");
        registers.writeAccumulator(-50);
        registers.writeRegister(RegisterName.AX, -10);
        registers.setProgramCounter(2000);

        assertEquals(Optional.of("instruction"), registers.instructionRegister());
    }

    @Test
    void shouldSupportDifferentInstructionRegisterTypes() {
        CpuRegisters<Integer> registers = new CpuRegisters<>();

        registers.loadInstructionRegister(42);

        assertEquals(Optional.of(42), registers.instructionRegister());
    }
}
