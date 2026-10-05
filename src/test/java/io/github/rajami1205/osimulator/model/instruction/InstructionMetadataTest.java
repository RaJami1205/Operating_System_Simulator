package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.exception.InvalidImmediateValueException;
import io.github.rajami1205.osimulator.model.instruction.operand.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class InstructionMetadataTest {
    @ParameterizedTest
    @EnumSource(RegisterName.class)
    void registerOperandsPreserveTheExistingRegisterContract(RegisterName register) {
        assertEquals(register, new RegisterOperand(register).register());
        assertEquals(new RegisterOperand(register), new RegisterOperand(register));
    }

    @Test
    void registerOperandRejectsNullAndDoesNotExpandRegisters() {
        assertThrows(NullPointerException.class, () -> new RegisterOperand(null));
        assertArrayEquals(new RegisterName[]{RegisterName.AX, RegisterName.BX, RegisterName.CX, RegisterName.DX}, RegisterName.values());
    }

    @ParameterizedTest
    @ValueSource(ints = {-32768, -1, 0, 32767})
    void immediateOperandAcceptsSigned16Values(int value) {
        assertEquals(value, new ImmediateOperand(value).value());
        assertEquals(new ImmediateOperand(value), new ImmediateOperand(value));
    }

    @ParameterizedTest
    @ValueSource(ints = {-32769, 32768, Integer.MIN_VALUE, Integer.MAX_VALUE})
    void immediateOperandPreservesDomainException(int value) {
        assertThrows(InvalidImmediateValueException.class, () -> new ImmediateOperand(value));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    void weightRejectsNonPositiveTicks(int ticks) {
        assertThrows(IllegalArgumentException.class, () -> new ExecutionWeight(ticks));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, Integer.MAX_VALUE})
    void weightAcceptsPositiveTicks(int ticks) {
        assertEquals(ticks, new ExecutionWeight(ticks).ticks());
        assertEquals(new ExecutionWeight(ticks), new ExecutionWeight(ticks));
    }

    @Test
    void exposesOfficialWeightsAndOrderedImmutableViewsWithoutChangingRecordEquality() {
        List<Instruction> instructions = List.of(new MovInstruction(RegisterName.AX, -5),
                new LoadInstruction(RegisterName.BX), new StoreInstruction(RegisterName.CX),
                new AddInstruction(RegisterName.DX), new SubInstruction(RegisterName.AX));
        List<Instruction> equalInstructions = List.of(new MovInstruction(RegisterName.AX, -5),
                new LoadInstruction(RegisterName.BX), new StoreInstruction(RegisterName.CX),
                new AddInstruction(RegisterName.DX), new SubInstruction(RegisterName.AX));
        List<List<InstructionOperand>> expected = List.of(
                List.of(new RegisterOperand(RegisterName.AX), new ImmediateOperand(-5)),
                List.of(new RegisterOperand(RegisterName.BX)), List.of(new RegisterOperand(RegisterName.CX)),
                List.of(new RegisterOperand(RegisterName.DX)), List.of(new RegisterOperand(RegisterName.AX)));
        int[] weights = {1, 2, 2, 3, 3};
        for (int index = 0; index < instructions.size(); index++) {
            var instruction = instructions.get(index);
            assertEquals(weights[index], instruction.executionWeight().ticks());
            assertEquals(expected.get(index), instruction.operands());
            assertThrows(UnsupportedOperationException.class, () -> instruction.operands().clear());
            assertThrows(UnsupportedOperationException.class, () -> instruction.operands().set(0, new ImmediateOperand(0)));
            assertEquals(equalInstructions.get(index), instruction);
            assertEquals(equalInstructions.get(index).hashCode(), instruction.hashCode());
            assertTrue(instruction.getClass().isRecord());
        }
        assertNotEquals(instructions.getFirst(), new MovInstruction(RegisterName.AX, 5));
    }
}
