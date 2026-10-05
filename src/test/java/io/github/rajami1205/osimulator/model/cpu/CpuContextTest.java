package io.github.rajami1205.osimulator.model.cpu;

import io.github.rajami1205.osimulator.model.cpu.exception.InvalidProgramCounterException;
import io.github.rajami1205.osimulator.model.cpu.exception.InvalidRegisterValueException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CpuContextTest {
    private CpuContext<String> populatedContext() {
        return new CpuContext<>(100000, Optional.of("MOV AX, 5"), -32768,
                32767, 2, 3, 4, 5, 6, new ConditionFlags(true, true));
    }

    @Test
    void restoresEveryFieldAndSnapshotsRemainIndependent() {
        var cpu = new CpuRegisters<String>();
        var saved = populatedContext();
        cpu.restore(saved);
        assertEquals(saved, cpu.snapshot());
        assertEquals(100000, cpu.programCounter());
        assertEquals(Optional.of("MOV AX, 5"), cpu.instructionRegister());
        assertEquals(-32768, cpu.accumulator());
        assertEquals(32767, cpu.readRegister(RegisterName.AX));
        assertEquals(2, cpu.readRegister(RegisterName.BX));
        assertEquals(3, cpu.readRegister(RegisterName.CX));
        assertEquals(4, cpu.readRegister(RegisterName.DX));
        assertEquals(5, cpu.ah());
        assertEquals(6, cpu.al());
        assertEquals(new ConditionFlags(true, true), cpu.conditionFlags());
        cpu.writeRegister(RegisterName.AX, 12);
        cpu.writeAh(13);
        cpu.writeConditionFlags(ConditionFlags.CLEAR);
        cpu.reset();
        cpu.reset();
        assertEquals(new CpuRegisters<String>().snapshot(), cpu.snapshot());
        assertEquals(populatedContext(), saved);
        cpu.restore(saved);
        assertEquals(saved, cpu.snapshot());
        cpu.restore(new CpuRegisters<String>().snapshot());
        assertTrue(cpu.instructionRegister().isEmpty());
    }

    @Test
    void rejectsNullRestoreAndFlagsWithoutMutation() {
        var cpu = new CpuRegisters<String>();
        cpu.restore(populatedContext());
        var before = cpu.snapshot();
        assertThrows(NullPointerException.class, () -> cpu.restore(null));
        assertThrows(NullPointerException.class, () -> cpu.writeConditionFlags(null));
        assertEquals(before, cpu.snapshot());
    }

    @ParameterizedTest
    @ValueSource(ints = {-32768, -128, 128, 32767})
    void serviceRegistersAreIndependentAndNumeric(int value) {
        var cpu = new CpuRegisters<Integer>();
        cpu.writeRegister(RegisterName.AX, 42);
        cpu.writeAh(value);
        assertEquals(0, cpu.al());
        cpu.writeAl(value);
        assertEquals(value, cpu.ah());
        assertEquals(value, cpu.al());
        assertEquals(42, cpu.readRegister(RegisterName.AX));
        cpu.writeRegister(RegisterName.AX, 7);
        assertEquals(value, cpu.ah());
        assertEquals(value, cpu.al());
        cpu.loadInstructionRegister(123);
        assertEquals(Optional.of(123), cpu.snapshot().instructionRegister());
    }

    @ParameterizedTest
    @ValueSource(ints = {-32769, 32768, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void rejectsEveryInvalidDataFieldAndPreservesServiceRegisters(int invalid) {
        for (int index = 0; index < 7; index++) {
            int[] values = new int[7];
            values[index] = invalid;
            assertThrows(InvalidRegisterValueException.class, () -> new CpuContext<>(0, Optional.empty(),
                    values[0], values[1], values[2], values[3], values[4], values[5], values[6], ConditionFlags.CLEAR));
        }
        var cpu = new CpuRegisters<String>();
        cpu.restore(populatedContext());
        assertThrows(InvalidRegisterValueException.class, () -> cpu.writeAh(invalid));
        assertThrows(InvalidRegisterValueException.class, () -> cpu.writeAl(invalid));
        assertEquals(populatedContext(), cpu.snapshot());
    }

    @Test
    void validatesPcAndRequiredReferences() {
        assertThrows(InvalidProgramCounterException.class, () -> new CpuContext<>(-1, Optional.empty(),
                0, 0, 0, 0, 0, 0, 0, ConditionFlags.CLEAR));
        assertThrows(NullPointerException.class, () -> new CpuContext<>(0, null,
                0, 0, 0, 0, 0, 0, 0, ConditionFlags.CLEAR));
        assertThrows(NullPointerException.class, () -> new CpuContext<>(0, Optional.empty(),
                0, 0, 0, 0, 0, 0, 0, null));
        assertDoesNotThrow(() -> new CpuContext<>(Integer.MAX_VALUE, Optional.empty(),
                0, 0, 0, 0, 0, 0, 0, ConditionFlags.CLEAR));
    }
}
