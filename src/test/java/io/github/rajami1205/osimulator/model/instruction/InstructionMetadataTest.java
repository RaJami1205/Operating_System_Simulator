package io.github.rajami1205.osimulator.model.instruction;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.exception.InvalidImmediateValueException;
import io.github.rajami1205.osimulator.model.instruction.operand.*;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.filesystem.SimulatedFileSystem;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import io.github.rajami1205.osimulator.model.io.ScreenDevice;
import io.github.rajami1205.osimulator.model.execution.TickResult;
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
    @EnumSource(Opcode.class)
    void everyOpcodeCompletesItsInstructionInOneTick(Opcode opcode) {
        Instruction instruction = switch (opcode) {
            case MOV -> new MovInstruction(RegisterName.AX, 7);
            case LOAD -> new LoadInstruction(RegisterName.AX);
            case STORE -> new StoreInstruction(RegisterName.AX);
            case ADD -> new AddInstruction(RegisterName.AX);
            case SUB -> new SubInstruction(RegisterName.AX);
            case INC -> new IncInstruction();
            case DEC -> new DecInstruction();
            case SWAP -> new SwapInstruction(RegisterName.AX, RegisterName.BX);
            case CMP -> new CmpInstruction(RegisterName.AX, RegisterName.BX);
            case JMP -> new JmpInstruction(new BranchDisplacement(0));
            case JE -> new JeInstruction(new BranchDisplacement(0));
            case JNE -> new JneInstruction(new BranchDisplacement(0));
            case PARAM -> new ParamInstruction(List.of(new ImmediateOperand(7)));
            case PUSH -> new PushInstruction(RegisterName.AX);
            case POP -> new PopInstruction(RegisterName.AX);
            case INT -> new InterruptInstruction(InterruptVector.TERMINATE);
        };
        var memory = new MainMemory(
                new MemoryConfiguration(128, 32));
        var loaded = new ProgramLoader()
                .loadWithAllocation(memory, 1, List.of(instruction));
        var cpu = new CpuRegisters<Instruction>();
        loaded.pcb().stack().push(7);
        var result = new ExecutionEngine().executeTick(
                new SimulatedFileSystem(
                        new SecondaryStorage(128, 64)),
                new ScreenDevice(),
                memory, cpu, loaded.pcb(), loaded.userAllocation());
        assertEquals(opcode, cpu.instructionRegister().orElseThrow().opcode());
        boolean loops = opcode == Opcode.JMP || opcode == Opcode.JNE;
        assertEquals(loops ? TickResult.INSTRUCTION_COMPLETED
                : TickResult.PROGRAM_FINISHED, result);
        assertEquals(loops || opcode == Opcode.INT ? 4096 : 4097, loaded.pcb().programCounter());
    }

    @Test
    void instructionOperandsRemainImmutableAfterSourceMutation() {
        var values = new java.util.ArrayList<>(List.of(new ImmediateOperand(1), new ImmediateOperand(2)));
        var instruction = new ParamInstruction(values);
        values.clear();
        assertEquals(List.of(new ImmediateOperand(1), new ImmediateOperand(2)), instruction.operands());
        assertThrows(UnsupportedOperationException.class, () -> instruction.operands().clear());
    }

    @Test
    void exposesOrderedImmutableViewsWithoutChangingRecordEquality() {
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
        for (int index = 0; index < instructions.size(); index++) {
            var instruction = instructions.get(index);
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
