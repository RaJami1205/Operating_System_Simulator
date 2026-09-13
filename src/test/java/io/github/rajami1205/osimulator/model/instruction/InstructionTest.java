package io.github.rajami1205.osimulator.model.instruction;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.exception.InvalidImmediateValueException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class InstructionTest {

    @Test
    void shouldDefineExactlyTheApprovedOpcodes() {
        assertArrayEquals(
                new Opcode[]{
                    Opcode.MOV,
                    Opcode.LOAD,
                    Opcode.STORE,
                    Opcode.ADD,
                    Opcode.SUB
                },
                Opcode.values()
        );
    }

    @Test
    void shouldPreserveLoadSourceAndExposeFixedOpcode() {
        LoadInstruction instruction = new LoadInstruction(RegisterName.AX);

        assertAll(
                () -> assertEquals(RegisterName.AX, instruction.source()),
                () -> assertEquals(Opcode.LOAD, instruction.opcode())
        );
    }

    @Test
    void shouldRejectNullLoadSource() {
        assertThrows(NullPointerException.class, () -> new LoadInstruction(null));
    }

    @Test
    void shouldPreserveStoreDestinationAndExposeFixedOpcode() {
        StoreInstruction instruction = new StoreInstruction(RegisterName.BX);

        assertAll(
                () -> assertEquals(RegisterName.BX, instruction.destination()),
                () -> assertEquals(Opcode.STORE, instruction.opcode())
        );
    }

    @Test
    void shouldRejectNullStoreDestination() {
        assertThrows(NullPointerException.class, () -> new StoreInstruction(null));
    }

    @Test
    void shouldPreserveAddSourceAndExposeFixedOpcode() {
        AddInstruction instruction = new AddInstruction(RegisterName.CX);

        assertAll(
                () -> assertEquals(RegisterName.CX, instruction.source()),
                () -> assertEquals(Opcode.ADD, instruction.opcode())
        );
    }

    @Test
    void shouldRejectNullAddSource() {
        assertThrows(NullPointerException.class, () -> new AddInstruction(null));
    }

    @Test
    void shouldPreserveSubSourceAndExposeFixedOpcode() {
        SubInstruction instruction = new SubInstruction(RegisterName.DX);

        assertAll(
                () -> assertEquals(RegisterName.DX, instruction.source()),
                () -> assertEquals(Opcode.SUB, instruction.opcode())
        );
    }

    @Test
    void shouldRejectNullSubSource() {
        assertThrows(NullPointerException.class, () -> new SubInstruction(null));
    }

    @Test
    void shouldPreserveMovOperandsAndExposeFixedOpcode() {
        MovInstruction instruction = new MovInstruction(RegisterName.AX, 25);

        assertAll(
                () -> assertEquals(RegisterName.AX, instruction.destination()),
                () -> assertEquals(25, instruction.immediate()),
                () -> assertEquals(Opcode.MOV, instruction.opcode())
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {-127, 127})
    void shouldAcceptMovImmediateBoundaryValues(int immediate) {
        MovInstruction instruction = new MovInstruction(RegisterName.AX, immediate);

        assertEquals(immediate, instruction.immediate());
    }

    @ParameterizedTest
    @ValueSource(ints = {-128, 128})
    void shouldRejectMovImmediateValuesOutsideRange(int immediate) {
        InvalidImmediateValueException exception = assertThrows(
                InvalidImmediateValueException.class,
                () -> new MovInstruction(RegisterName.AX, immediate)
        );

        assertAll(
                () -> assertNotNull(exception.getMessage()),
                () -> assertFalse(exception.getMessage().isBlank())
        );
    }

    @Test
    void shouldRejectNullMovDestinationBeforeImmediateValidation() {
        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new MovInstruction(null, 0)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> new MovInstruction(null, 128)
                )
        );
    }

    @ParameterizedTest
    @EnumSource(RegisterName.class)
    void shouldSupportEveryRegisterAsMovDestination(RegisterName destination) {
        MovInstruction instruction = new MovInstruction(destination, 0);

        assertEquals(destination, instruction.destination());
    }

    @Test
    void shouldExposeOpcodesPolymorphically() {
        List<Instruction> instructions = List.of(
                new MovInstruction(RegisterName.AX, 25),
                new LoadInstruction(RegisterName.AX),
                new StoreInstruction(RegisterName.BX),
                new AddInstruction(RegisterName.CX),
                new SubInstruction(RegisterName.DX)
        );

        assertAll(
                () -> assertEquals(Opcode.MOV, instructions.get(0).opcode()),
                () -> assertEquals(Opcode.LOAD, instructions.get(1).opcode()),
                () -> assertEquals(Opcode.STORE, instructions.get(2).opcode()),
                () -> assertEquals(Opcode.ADD, instructions.get(3).opcode()),
                () -> assertEquals(Opcode.SUB, instructions.get(4).opcode())
        );
    }
}
