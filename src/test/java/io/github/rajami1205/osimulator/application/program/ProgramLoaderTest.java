package io.github.rajami1205.osimulator.application.program;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rajami1205.osimulator.application.program.exception.ProgramLoadException;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.AddInstruction;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.MovInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import io.github.rajami1205.osimulator.model.memory.Memory;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessConfigurationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProgramLoaderTest {

    @Test
    void shouldLoadProgramInOrderAndReturnReadyProcessControlBlock() {
        Memory<Instruction> memory = createMemory();
        List<Instruction> program = createRepresentativeProgram();

        ProcessControlBlock pcb = new ProgramLoader().load(memory, 7, program);

        assertProgramStoredAt(memory, 32, program);
        assertAll(
                () -> assertEquals(7, pcb.processId()),
                () -> assertEquals(32, pcb.programStartAddress()),
                () -> assertEquals(5, pcb.instructionCount()),
                () -> assertEquals(37, pcb.programEndAddressExclusive()),
                () -> assertEquals(32, pcb.programCounter()),
                () -> assertEquals(ProcessState.READY, pcb.state())
        );
    }

    @Test
    void shouldRejectNullMemory() {
        ProgramLoader loader = new ProgramLoader();

        assertThrows(
                NullPointerException.class,
                () -> loader.load(null, 1, createRepresentativeProgram())
        );
    }

    @Test
    void shouldRejectNullInstructionListWithoutModifyingMemory() {
        Memory<Instruction> memory = createMemory();

        assertThrows(
                NullPointerException.class,
                () -> new ProgramLoader().load(memory, 1, null)
        );

        assertUserMemoryIsEmpty(memory);
    }

    @Test
    void shouldRejectNullInstructionElementWithoutModifyingMemory() {
        Memory<Instruction> memory = createMemory();
        List<Instruction> program = new ArrayList<>();
        program.add(new LoadInstruction(RegisterName.AX));
        program.add(null);
        program.add(new AddInstruction(RegisterName.BX));

        assertThrows(
                NullPointerException.class,
                () -> new ProgramLoader().load(memory, 1, program)
        );

        assertUserMemoryIsEmpty(memory);
    }

    @Test
    void shouldRejectEmptyProgramWithoutModifyingMemory() {
        Memory<Instruction> memory = createMemory();

        ProgramLoadException exception = assertProgramLoadFails(
                new ProgramLoader(),
                memory,
                1,
                List.of()
        );

        assertValidMessage(exception);
        assertUserMemoryIsEmpty(memory);
    }

    @Test
    void shouldWrapInvalidProcessConfigurationWithoutModifyingMemory() {
        Memory<Instruction> memory = createMemory();

        ProgramLoadException exception = assertProgramLoadFails(
                new ProgramLoader(),
                memory,
                0,
                createRepresentativeProgram()
        );

        assertAll(
                () -> assertValidMessage(exception),
                () -> assertInstanceOf(
                        InvalidProcessConfigurationException.class,
                        exception.getCause()
                )
        );
        assertUserMemoryIsEmpty(memory);
    }

    @Test
    void shouldRejectProgramLargerThanUserMemoryWithoutModifyingMemory() {
        Memory<Instruction> memory = createMemory();
        int oversizedProgramLength = memory.configuration().userPositions() + 1;
        List<Instruction> program = Collections.nCopies(
                oversizedProgramLength,
                new LoadInstruction(RegisterName.AX)
        );

        ProgramLoadException exception = assertProgramLoadFails(
                new ProgramLoader(),
                memory,
                1,
                program
        );

        assertValidMessage(exception);
        assertUserMemoryIsEmpty(memory);
    }

    @Test
    void shouldLoadProgramThatExactlyFillsUserMemory() {
        Memory<Instruction> memory = createMemory();
        int userPositions = memory.configuration().userPositions();
        Instruction instruction = new LoadInstruction(RegisterName.AX);
        List<Instruction> program = Collections.nCopies(userPositions, instruction);

        ProcessControlBlock pcb = new ProgramLoader().load(memory, 1, program);

        assertAll(
                () -> assertSame(instruction, memory.read(32).orElseThrow()),
                () -> assertSame(instruction, memory.read(127).orElseThrow()),
                () -> assertEquals(32, pcb.programStartAddress()),
                () -> assertEquals(userPositions, pcb.instructionCount()),
                () -> assertEquals(memory.size(), pcb.programEndAddressExclusive()),
                () -> assertEquals(32, pcb.programCounter()),
                () -> assertEquals(ProcessState.READY, pcb.state())
        );
    }

    @Test
    void shouldRejectOccupiedFirstTargetWithoutOverwritingMemory() {
        Memory<Instruction> memory = createMemory();
        Instruction existingInstruction = new LoadInstruction(RegisterName.DX);
        memory.writeUser(32, existingInstruction);

        ProgramLoadException exception = assertProgramLoadFails(
                new ProgramLoader(),
                memory,
                1,
                createRepresentativeProgram()
        );

        assertAll(
                () -> assertValidMessage(exception),
                () -> assertSame(existingInstruction, memory.read(32).orElseThrow()),
                () -> assertTrue(memory.isEmpty(33)),
                () -> assertTrue(memory.isEmpty(34)),
                () -> assertTrue(memory.isEmpty(35)),
                () -> assertTrue(memory.isEmpty(36))
        );
    }

    @Test
    void shouldRejectOccupiedMiddleTargetBeforeWritingAnyProgramInstruction() {
        Memory<Instruction> memory = createMemory();
        Instruction existingInstruction = new StoreInstruction(RegisterName.DX);
        memory.writeUser(34, existingInstruction);

        ProgramLoadException exception = assertProgramLoadFails(
                new ProgramLoader(),
                memory,
                1,
                createRepresentativeProgram()
        );

        assertAll(
                () -> assertValidMessage(exception),
                () -> assertTrue(memory.isEmpty(32)),
                () -> assertTrue(memory.isEmpty(33)),
                () -> assertSame(existingInstruction, memory.read(34).orElseThrow()),
                () -> assertTrue(memory.isEmpty(35)),
                () -> assertTrue(memory.isEmpty(36))
        );
    }

    @Test
    void shouldIgnoreAndPreserveOccupiedUserMemoryOutsideTarget() {
        Memory<Instruction> memory = createMemory();
        Instruction unrelatedInstruction = new LoadInstruction(RegisterName.DX);
        memory.writeUser(90, unrelatedInstruction);
        List<Instruction> program = createRepresentativeProgram();

        ProcessControlBlock pcb = new ProgramLoader().load(memory, 1, program);

        assertAll(
                () -> assertEquals(ProcessState.READY, pcb.state()),
                () -> assertSame(unrelatedInstruction, memory.read(90).orElseThrow())
        );
        assertProgramStoredAt(memory, 32, program);
    }

    @Test
    void shouldLeaveKernelBoundaryAndPositionAfterTargetUntouched() {
        Memory<Instruction> memory = createMemory();
        Instruction trailingInstruction = new SubInstruction(RegisterName.AX);
        memory.writeUser(37, trailingInstruction);

        new ProgramLoader().load(memory, 1, createRepresentativeProgram());

        assertAll(
                () -> assertTrue(memory.isEmpty(31)),
                () -> assertSame(trailingInstruction, memory.read(37).orElseThrow())
        );
    }

    @Test
    void shouldReuseLoaderAcrossIndependentMemoryInstances() {
        ProgramLoader loader = new ProgramLoader();
        Memory<Instruction> firstMemory = createMemory();
        Memory<Instruction> secondMemory = createMemory();
        List<Instruction> firstProgram = List.of(new LoadInstruction(RegisterName.AX));
        List<Instruction> secondProgram = List.of(new StoreInstruction(RegisterName.BX));

        ProcessControlBlock firstPcb = loader.load(firstMemory, 1, firstProgram);
        ProcessControlBlock secondPcb = loader.load(secondMemory, 2, secondProgram);

        assertAll(
                () -> assertEquals(1, firstPcb.processId()),
                () -> assertEquals(2, secondPcb.processId()),
                () -> assertEquals(firstProgram.get(0), firstMemory.read(32).orElseThrow()),
                () -> assertEquals(secondProgram.get(0), secondMemory.read(32).orElseThrow())
        );
    }

    private Memory<Instruction> createMemory() {
        return new Memory<>(new MemoryConfiguration(128, 32));
    }

    private List<Instruction> createRepresentativeProgram() {
        return List.of(
                new MovInstruction(RegisterName.AX, 5),
                new LoadInstruction(RegisterName.AX),
                new AddInstruction(RegisterName.BX),
                new StoreInstruction(RegisterName.CX),
                new SubInstruction(RegisterName.DX)
        );
    }

    private void assertProgramStoredAt(
            Memory<Instruction> memory,
            int startAddress,
            List<Instruction> program
    ) {
        for (int index = 0; index < program.size(); index++) {
            assertEquals(
                    program.get(index),
                    memory.read(startAddress + index).orElseThrow()
            );
        }
    }

    private ProgramLoadException assertProgramLoadFails(
            ProgramLoader loader,
            Memory<Instruction> memory,
            int processId,
            List<Instruction> program
    ) {
        return assertThrows(
                ProgramLoadException.class,
                () -> loader.load(memory, processId, program)
        );
    }

    private void assertValidMessage(ProgramLoadException exception) {
        assertNotNull(exception.getMessage());
        assertFalse(exception.getMessage().isBlank());
    }

    private void assertUserMemoryIsEmpty(Memory<Instruction> memory) {
        for (int address = memory.configuration().userStartAddress();
                address < memory.size();
                address++) {
            assertTrue(memory.isEmpty(address));
        }
    }
}
