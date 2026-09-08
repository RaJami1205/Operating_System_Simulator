package io.github.rajami1205.osimulator.model.instruction;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import java.util.List;
import org.junit.jupiter.api.Test;

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
    void shouldExposeOpcodesPolymorphically() {
        List<Instruction> instructions = List.of(
                new LoadInstruction(RegisterName.AX),
                new StoreInstruction(RegisterName.BX),
                new AddInstruction(RegisterName.CX),
                new SubInstruction(RegisterName.DX)
        );

        assertAll(
                () -> assertEquals(Opcode.LOAD, instructions.get(0).opcode()),
                () -> assertEquals(Opcode.STORE, instructions.get(1).opcode()),
                () -> assertEquals(Opcode.ADD, instructions.get(2).opcode()),
                () -> assertEquals(Opcode.SUB, instructions.get(3).opcode())
        );
    }
}
