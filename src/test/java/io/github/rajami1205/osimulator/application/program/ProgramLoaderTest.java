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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        memory.writeUser(90, new LoadInstruction(RegisterName.DX));
        List<Optional<Instruction>> memoryBeforeLoad = snapshotMemory(memory);

        assertThrows(
                NullPointerException.class,
                () -> new ProgramLoader().load(memory, 1, null)
        );

        assertMemoryMatchesSnapshot(memory, memoryBeforeLoad);
    }

    @Test
    void shouldRejectNullInstructionElementWithoutModifyingMemory() {
        Memory<Instruction> memory = createMemory();
        List<Instruction> program = new ArrayList<>();
        program.add(new LoadInstruction(RegisterName.AX));
        program.add(new StoreInstruction(RegisterName.BX));
        program.add(null);
        program.add(new AddInstruction(RegisterName.BX));
        memory.writeUser(90, new SubInstruction(RegisterName.DX));
        List<Optional<Instruction>> memoryBeforeLoad = snapshotMemory(memory);

        assertThrows(
                NullPointerException.class,
                () -> new ProgramLoader().load(memory, 1, program)
        );

        assertMemoryMatchesSnapshot(memory, memoryBeforeLoad);
    }

    @Test
    void shouldRejectEmptyProgramWithoutModifyingMemory() {
        Memory<Instruction> memory = createMemory();
        memory.writeUser(90, new LoadInstruction(RegisterName.DX));
        List<Optional<Instruction>> memoryBeforeLoad = snapshotMemory(memory);

        ProgramLoadException exception = assertProgramLoadFails(
                new ProgramLoader(),
                memory,
                1,
                List.of()
        );

        assertValidMessage(exception);
        assertMemoryMatchesSnapshot(memory, memoryBeforeLoad);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -7})
    void shouldWrapInvalidProcessConfigurationWithoutModifyingMemory(int processId) {
        Memory<Instruction> memory = createMemory();
        memory.writeUser(90, new StoreInstruction(RegisterName.DX));
        List<Optional<Instruction>> memoryBeforeLoad = snapshotMemory(memory);

        ProgramLoadException exception = assertProgramLoadFails(
                new ProgramLoader(),
                memory,
                processId,
                createRepresentativeProgram()
        );

        assertAll(
                () -> assertValidMessage(exception),
                () -> assertInstanceOf(
                        InvalidProcessConfigurationException.class,
                        exception.getCause()
                )
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeLoad);
    }

    @Test
    void shouldRejectProgramLargerThanUserMemoryWithoutModifyingMemory() {
        Memory<Instruction> memory = createMemory();
        int oversizedProgramLength = memory.configuration().userPositions() + 1;
        List<Instruction> program = Collections.nCopies(
                oversizedProgramLength,
                new LoadInstruction(RegisterName.AX)
        );
        memory.writeUser(90, new StoreInstruction(RegisterName.DX));
        List<Optional<Instruction>> memoryBeforeLoad = snapshotMemory(memory);

        ProgramLoadException exception = assertProgramLoadFails(
                new ProgramLoader(),
                memory,
                1,
                program
        );

        assertValidMessage(exception);
        assertMemoryMatchesSnapshot(memory, memoryBeforeLoad);
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
    void shouldLoadProgramOnePositionShortOfUserMemoryCapacity() {
        Memory<Instruction> memory = createMemory();
        int lastMemoryAddress = memory.size() - 1;
        int programSize = memory.configuration().userPositions() - 1;
        Instruction programInstruction = new LoadInstruction(RegisterName.AX);
        Instruction trailingInstruction = new StoreInstruction(RegisterName.DX);
        List<Instruction> program = Collections.nCopies(programSize, programInstruction);
        memory.writeUser(lastMemoryAddress, trailingInstruction);

        ProcessControlBlock pcb = new ProgramLoader().load(memory, 1, program);

        assertAll(
                () -> assertSame(programInstruction, memory.read(32).orElseThrow()),
                () -> assertSame(
                        programInstruction,
                        memory.read(lastMemoryAddress - 1).orElseThrow()
                ),
                () -> assertSame(
                        trailingInstruction,
                        memory.read(lastMemoryAddress).orElseThrow()
                ),
                () -> assertEquals(lastMemoryAddress, pcb.programEndAddressExclusive()),
                () -> assertEquals(pcb.programStartAddress(), pcb.programCounter()),
                () -> assertEquals(ProcessState.READY, pcb.state())
        );
    }

    @Test
    void shouldLoadSingleInstructionProgramAtMinimumBoundary() {
        Memory<Instruction> memory = createMemory();
        Instruction instruction = new StoreInstruction(RegisterName.BX);

        ProcessControlBlock pcb = new ProgramLoader().load(memory, 5, List.of(instruction));

        assertAll(
                () -> assertSame(instruction, memory.read(32).orElseThrow()),
                () -> assertTrue(memory.isEmpty(33)),
                () -> assertEquals(5, pcb.processId()),
                () -> assertEquals(32, pcb.programStartAddress()),
                () -> assertEquals(1, pcb.instructionCount()),
                () -> assertEquals(33, pcb.programEndAddressExclusive()),
                () -> assertEquals(pcb.programStartAddress(), pcb.programCounter()),
                () -> assertEquals(ProcessState.READY, pcb.state())
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {32, 34, 36})
    void shouldRejectAnyOccupiedTargetWithoutChangingMemory(int occupiedAddress) {
        Memory<Instruction> memory = createMemory();
        Instruction existingInstruction = new StoreInstruction(RegisterName.DX);
        Instruction unrelatedInstruction = new LoadInstruction(RegisterName.CX);
        memory.writeUser(occupiedAddress, existingInstruction);
        memory.writeUser(90, unrelatedInstruction);
        List<Optional<Instruction>> memoryBeforeLoad = snapshotMemory(memory);

        ProgramLoadException exception = assertProgramLoadFails(
                new ProgramLoader(),
                memory,
                1,
                createRepresentativeProgram()
        );

        assertValidMessage(exception);
        assertMemoryMatchesSnapshot(memory, memoryBeforeLoad);
        assertAll(
                () -> assertSame(
                        existingInstruction,
                        memory.read(occupiedAddress).orElseThrow()
                ),
                () -> assertSame(unrelatedInstruction, memory.read(90).orElseThrow())
        );
    }

    @Test
    void shouldRejectShorterSecondLoadWithoutOverwritingOrSearchingForSpace() {
        ProgramLoader loader = new ProgramLoader();
        Memory<Instruction> memory = createMemory();
        List<Instruction> firstProgram = createRepresentativeProgram();
        loader.load(memory, 1, firstProgram);
        List<Optional<Instruction>> memoryAfterFirstLoad = snapshotMemory(memory);
        List<Instruction> shorterProgram = List.of(
                new StoreInstruction(RegisterName.AX),
                new SubInstruction(RegisterName.BX)
        );

        ProgramLoadException exception = assertProgramLoadFails(
                loader,
                memory,
                2,
                shorterProgram
        );

        assertValidMessage(exception);
        assertMemoryMatchesSnapshot(memory, memoryAfterFirstLoad);
        assertProgramStoredAt(memory, 32, firstProgram);
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
        Optional<Instruction> kernelBoundaryBeforeLoad = memory.read(31);

        new ProgramLoader().load(memory, 1, createRepresentativeProgram());

        assertAll(
                () -> assertEquals(kernelBoundaryBeforeLoad, memory.read(31)),
                () -> assertSame(trailingInstruction, memory.read(37).orElseThrow())
        );
    }

    @Test
    void shouldNotRetainMutableInputListAfterSuccessfulLoad() {
        Memory<Instruction> memory = createMemory();
        Instruction firstInstruction = new LoadInstruction(RegisterName.AX);
        Instruction secondInstruction = new StoreInstruction(RegisterName.BX);
        List<Instruction> mutableProgram = new ArrayList<>();
        mutableProgram.add(firstInstruction);
        mutableProgram.add(secondInstruction);

        ProcessControlBlock pcb = new ProgramLoader().load(memory, 3, mutableProgram);

        mutableProgram.set(0, new AddInstruction(RegisterName.CX));
        mutableProgram.clear();

        assertAll(
                () -> assertSame(firstInstruction, memory.read(32).orElseThrow()),
                () -> assertSame(secondInstruction, memory.read(33).orElseThrow()),
                () -> assertEquals(2, pcb.instructionCount()),
                () -> assertEquals(34, pcb.programEndAddressExclusive()),
                () -> assertEquals(32, pcb.programCounter()),
                () -> assertEquals(ProcessState.READY, pcb.state())
        );
    }

    @Test
    void shouldReuseLoaderAcrossIndependentMemoryInstances() {
        ProgramLoader loader = new ProgramLoader();
        Memory<Instruction> firstMemory = createMemory();
        Memory<Instruction> secondMemory = createMemory();
        List<Instruction> firstProgram = List.of(new LoadInstruction(RegisterName.AX));
        List<Instruction> secondProgram = List.of(
                new StoreInstruction(RegisterName.BX),
                new AddInstruction(RegisterName.CX)
        );

        ProcessControlBlock firstPcb = loader.load(firstMemory, 1, firstProgram);
        ProcessControlBlock secondPcb = loader.load(secondMemory, 2, secondProgram);

        assertAll(
                () -> assertEquals(1, firstPcb.processId()),
                () -> assertEquals(2, secondPcb.processId()),
                () -> assertEquals(1, firstPcb.instructionCount()),
                () -> assertEquals(2, secondPcb.instructionCount()),
                () -> assertEquals(33, firstPcb.programEndAddressExclusive()),
                () -> assertEquals(34, secondPcb.programEndAddressExclusive()),
                () -> assertEquals(32, firstPcb.programCounter()),
                () -> assertEquals(32, secondPcb.programCounter()),
                () -> assertEquals(ProcessState.READY, firstPcb.state()),
                () -> assertEquals(ProcessState.READY, secondPcb.state()),
                () -> assertEquals(firstProgram.get(0), firstMemory.read(32).orElseThrow()),
                () -> assertEquals(secondProgram.get(0), secondMemory.read(32).orElseThrow()),
                () -> assertEquals(secondProgram.get(1), secondMemory.read(33).orElseThrow()),
                () -> assertTrue(firstMemory.isEmpty(33))
        );
    }

    @Test
    void shouldLoadSuccessfullyAfterPreviousFailureOnDifferentMemory() {
        ProgramLoader loader = new ProgramLoader();
        Memory<Instruction> failingMemory = createMemory();
        failingMemory.writeUser(34, new SubInstruction(RegisterName.DX));
        List<Optional<Instruction>> failingMemoryBeforeLoad = snapshotMemory(failingMemory);

        assertProgramLoadFails(loader, failingMemory, 1, createRepresentativeProgram());

        Memory<Instruction> successfulMemory = createMemory();
        Instruction instruction = new LoadInstruction(RegisterName.CX);
        ProcessControlBlock pcb = loader.load(successfulMemory, 2, List.of(instruction));

        assertMemoryMatchesSnapshot(failingMemory, failingMemoryBeforeLoad);
        assertAll(
                () -> assertSame(instruction, successfulMemory.read(32).orElseThrow()),
                () -> assertEquals(2, pcb.processId()),
                () -> assertEquals(1, pcb.instructionCount()),
                () -> assertEquals(32, pcb.programCounter()),
                () -> assertEquals(ProcessState.READY, pcb.state())
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

    private List<Optional<Instruction>> snapshotMemory(Memory<Instruction> memory) {
        List<Optional<Instruction>> snapshot = new ArrayList<>(memory.size());
        for (int address = 0; address < memory.size(); address++) {
            snapshot.add(memory.read(address));
        }
        return List.copyOf(snapshot);
    }

    private void assertMemoryMatchesSnapshot(
            Memory<Instruction> memory,
            List<Optional<Instruction>> expected
    ) {
        assertEquals(memory.size(), expected.size());
        for (int address = 0; address < memory.size(); address++) {
            assertEquals(
                    expected.get(address),
                    memory.read(address),
                    "Unexpected Memory change at address " + address
            );
        }
    }
}
