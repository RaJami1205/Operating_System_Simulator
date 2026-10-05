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
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.memory.MemoryContent;
import io.github.rajami1205.osimulator.model.memory.InstructionContent;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.exception.InvalidProcessConfigurationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProgramLoaderTest {

    @Test
    void shouldLoadProgramInOrderAndReturnReadyProcessControlBlock() {
        MainMemory memory = createMemory();
        List<Instruction> program = createRepresentativeProgram();

        ProcessControlBlock pcb = new ProgramLoader().load(memory, 7, program);

        assertEquals(io.github.rajami1205.osimulator.model.cpu.CpuContext.<Instruction>initial()
                .withProgramCounter(0), pcb.cpuContext());
        assertProgramStoredAt(memory, 32, program);
        assertAll(
                () -> assertEquals(7, pcb.processId()),
                () -> assertEquals(32, pcb.programStartAddress()),
                () -> assertEquals(5, pcb.instructionCount()),
                () -> assertEquals(37, pcb.programEndAddressExclusive()),
                () -> assertEquals(0, pcb.programCounter()),
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
        MainMemory memory = createMemory();
        writeAt(memory, 90, new LoadInstruction(RegisterName.DX));
        List<MemoryContent> memoryBeforeLoad = snapshotMemory(memory);

        assertThrows(
                NullPointerException.class,
                () -> new ProgramLoader().load(memory, 1, null)
        );

        assertMemoryMatchesSnapshot(memory, memoryBeforeLoad);
    }

    @Test
    void shouldRejectNullInstructionElementWithoutModifyingMemory() {
        MainMemory memory = createMemory();
        List<Instruction> program = new ArrayList<>();
        program.add(new LoadInstruction(RegisterName.AX));
        program.add(new StoreInstruction(RegisterName.BX));
        program.add(null);
        program.add(new AddInstruction(RegisterName.BX));
        writeAt(memory, 90, new SubInstruction(RegisterName.DX));
        List<MemoryContent> memoryBeforeLoad = snapshotMemory(memory);

        assertThrows(
                NullPointerException.class,
                () -> new ProgramLoader().load(memory, 1, program)
        );

        assertMemoryMatchesSnapshot(memory, memoryBeforeLoad);
    }

    @Test
    void shouldRejectEmptyProgramWithoutModifyingMemory() {
        MainMemory memory = createMemory();
        writeAt(memory, 90, new LoadInstruction(RegisterName.DX));
        List<MemoryContent> memoryBeforeLoad = snapshotMemory(memory);

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
        MainMemory memory = createMemory();
        writeAt(memory, 90, new StoreInstruction(RegisterName.DX));
        List<MemoryContent> memoryBeforeLoad = snapshotMemory(memory);

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
        assertEquals(32, memory.allocateUser(58).base());
    }

    @Test
    void shouldRejectProgramLargerThanUserMemoryWithoutModifyingMemory() {
        MainMemory memory = createMemory();
        int oversizedProgramLength = memory.configuration().userPositions() + 1;
        List<Instruction> program = Collections.nCopies(
                oversizedProgramLength,
                new LoadInstruction(RegisterName.AX)
        );
        writeAt(memory, 90, new StoreInstruction(RegisterName.DX));
        List<MemoryContent> memoryBeforeLoad = snapshotMemory(memory);

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
        MainMemory memory = createMemory();
        int userPositions = memory.configuration().userPositions();
        Instruction instruction = new LoadInstruction(RegisterName.AX);
        List<Instruction> program = Collections.nCopies(userPositions, instruction);

        ProcessControlBlock pcb = new ProgramLoader().load(memory, 1, program);

        assertAll(
                () -> assertSame(instruction, assertInstanceOf(InstructionContent.class, memory.read(32)).instruction()),
                () -> assertSame(instruction, assertInstanceOf(InstructionContent.class, memory.read(127)).instruction()),
                () -> assertEquals(32, pcb.programStartAddress()),
                () -> assertEquals(userPositions, pcb.instructionCount()),
                () -> assertEquals(memory.size(), pcb.programEndAddressExclusive()),
                () -> assertEquals(0, pcb.programCounter()),
                () -> assertEquals(ProcessState.READY, pcb.state())
        );
    }

    @Test
    void shouldLoadProgramOnePositionShortOfUserMemoryCapacity() {
        MainMemory memory = createMemory();
        int lastMemoryAddress = memory.size() - 1;
        int programSize = memory.configuration().userPositions() - 1;
        Instruction programInstruction = new LoadInstruction(RegisterName.AX);
        Instruction trailingInstruction = new StoreInstruction(RegisterName.DX);
        List<Instruction> program = Collections.nCopies(programSize, programInstruction);
        writeAt(memory, lastMemoryAddress, trailingInstruction);

        ProcessControlBlock pcb = new ProgramLoader().load(memory, 1, program);

        assertAll(
                () -> assertSame(programInstruction, assertInstanceOf(InstructionContent.class, memory.read(32)).instruction()),
                () -> assertSame(
                        programInstruction,
                        assertInstanceOf(InstructionContent.class, memory.read(lastMemoryAddress - 1)).instruction()
                ),
                () -> assertSame(
                        trailingInstruction,
                        assertInstanceOf(InstructionContent.class, memory.read(lastMemoryAddress)).instruction()
                ),
                () -> assertEquals(lastMemoryAddress, pcb.programEndAddressExclusive()),
                () -> assertEquals(0, pcb.programCounter()),
                () -> assertEquals(ProcessState.READY, pcb.state())
        );
    }

    @Test
    void shouldLoadSingleInstructionProgramAtMinimumBoundary() {
        MainMemory memory = createMemory();
        Instruction instruction = new StoreInstruction(RegisterName.BX);

        ProcessControlBlock pcb = new ProgramLoader().load(memory, 5, List.of(instruction));

        assertAll(
                () -> assertSame(instruction, assertInstanceOf(InstructionContent.class, memory.read(32)).instruction()),
                () -> assertTrue(memory.isEmpty(33)),
                () -> assertEquals(5, pcb.processId()),
                () -> assertEquals(32, pcb.programStartAddress()),
                () -> assertEquals(1, pcb.instructionCount()),
                () -> assertEquals(33, pcb.programEndAddressExclusive()),
                () -> assertEquals(0, pcb.programCounter()),
                () -> assertEquals(ProcessState.READY, pcb.state())
        );
    }

    @Test
    void shouldRespectReservedEmptyCellsAndPreserveExistingPrograms() {
        MainMemory memory = createMemory();
        var reserved = memory.allocateUser(5);
        assertTrue(memory.isEmpty(reserved.base()));
        ProcessControlBlock pcb = new ProgramLoader().load(memory, 1, createRepresentativeProgram());
        assertEquals(37, pcb.programStartAddress());
        assertEquals(0, pcb.programCounter());
        assertProgramStoredAt(memory, 37, createRepresentativeProgram());
        assertTrue(memory.isEmpty(32));
    }

    @Test
    void shouldLoadSecondProgramInNextFreeBlockWithoutOverwriting() {
        ProgramLoader loader = new ProgramLoader();
        MainMemory memory = createMemory();
        var first = createRepresentativeProgram();
        var second = List.<Instruction>of(new StoreInstruction(RegisterName.AX), new SubInstruction(RegisterName.BX));
        loader.load(memory, 1, first);
        var pcb = loader.load(memory, 2, second);
        assertEquals(37, pcb.programStartAddress());
        assertEquals(0, pcb.programCounter());
        assertProgramStoredAt(memory, 32, first);
        assertProgramStoredAt(memory, 37, second);
    }

    @Test
    void shouldIgnoreAndPreserveOccupiedUserMemoryOutsideTarget() {
        MainMemory memory = createMemory();
        Instruction unrelatedInstruction = new LoadInstruction(RegisterName.DX);
        writeAt(memory, 90, unrelatedInstruction);
        List<Instruction> program = createRepresentativeProgram();

        ProcessControlBlock pcb = new ProgramLoader().load(memory, 1, program);

        assertAll(
                () -> assertEquals(ProcessState.READY, pcb.state()),
                () -> assertSame(unrelatedInstruction, assertInstanceOf(InstructionContent.class, memory.read(90)).instruction())
        );
        assertEquals(io.github.rajami1205.osimulator.model.cpu.CpuContext.<Instruction>initial()
                .withProgramCounter(0), pcb.cpuContext());
        assertProgramStoredAt(memory, 32, program);
    }

    @Test
    void shouldLeaveKernelBoundaryAndPositionAfterTargetUntouched() {
        MainMemory memory = createMemory();
        Instruction trailingInstruction = new SubInstruction(RegisterName.AX);
        writeAt(memory, 37, trailingInstruction);
        MemoryContent kernelBoundaryBeforeLoad = memory.read(31);

        new ProgramLoader().load(memory, 1, createRepresentativeProgram());

        assertAll(
                () -> assertEquals(kernelBoundaryBeforeLoad, memory.read(31)),
                () -> assertSame(trailingInstruction, assertInstanceOf(InstructionContent.class, memory.read(37)).instruction())
        );
    }

    @Test
    void shouldNotRetainMutableInputListAfterSuccessfulLoad() {
        MainMemory memory = createMemory();
        Instruction firstInstruction = new LoadInstruction(RegisterName.AX);
        Instruction secondInstruction = new StoreInstruction(RegisterName.BX);
        List<Instruction> mutableProgram = new ArrayList<>();
        mutableProgram.add(firstInstruction);
        mutableProgram.add(secondInstruction);

        ProcessControlBlock pcb = new ProgramLoader().load(memory, 3, mutableProgram);

        mutableProgram.set(0, new AddInstruction(RegisterName.CX));
        mutableProgram.clear();

        assertAll(
                () -> assertSame(firstInstruction, assertInstanceOf(InstructionContent.class, memory.read(32)).instruction()),
                () -> assertSame(secondInstruction, assertInstanceOf(InstructionContent.class, memory.read(33)).instruction()),
                () -> assertEquals(2, pcb.instructionCount()),
                () -> assertEquals(34, pcb.programEndAddressExclusive()),
                () -> assertEquals(0, pcb.programCounter()),
                () -> assertEquals(ProcessState.READY, pcb.state())
        );
    }

    @Test
    void shouldReuseLoaderAcrossIndependentMemoryInstances() {
        ProgramLoader loader = new ProgramLoader();
        MainMemory firstMemory = createMemory();
        MainMemory secondMemory = createMemory();
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
                () -> assertEquals(0, firstPcb.programCounter()),
                () -> assertEquals(0, secondPcb.programCounter()),
                () -> assertEquals(ProcessState.READY, firstPcb.state()),
                () -> assertEquals(ProcessState.READY, secondPcb.state()),
                () -> assertEquals(firstProgram.get(0), assertInstanceOf(InstructionContent.class, firstMemory.read(32)).instruction()),
                () -> assertEquals(secondProgram.get(0), assertInstanceOf(InstructionContent.class, secondMemory.read(32)).instruction()),
                () -> assertEquals(secondProgram.get(1), assertInstanceOf(InstructionContent.class, secondMemory.read(33)).instruction()),
                () -> assertTrue(firstMemory.isEmpty(33))
        );
    }

    @Test
    void shouldLoadSuccessfullyAfterPreviousFailureOnDifferentMemory() {
        ProgramLoader loader = new ProgramLoader();
        MainMemory failingMemory = createMemory();
        failingMemory.allocateUser(failingMemory.configuration().userPositions());
        List<MemoryContent> failingMemoryBeforeLoad = snapshotMemory(failingMemory);

        assertProgramLoadFails(loader, failingMemory, 1, createRepresentativeProgram());

        MainMemory successfulMemory = createMemory();
        Instruction instruction = new LoadInstruction(RegisterName.CX);
        ProcessControlBlock pcb = loader.load(successfulMemory, 2, List.of(instruction));

        assertMemoryMatchesSnapshot(failingMemory, failingMemoryBeforeLoad);
        assertAll(
                () -> assertSame(instruction, assertInstanceOf(InstructionContent.class, successfulMemory.read(32)).instruction()),
                () -> assertEquals(2, pcb.processId()),
                () -> assertEquals(1, pcb.instructionCount()),
                () -> assertEquals(0, pcb.programCounter()),
                () -> assertEquals(ProcessState.READY, pcb.state())
        );
    }

    @Test
    void shouldRejectFragmentedSpaceAndRetryAfterCoalescingWithoutUsingKernel() {
        var memory = createMemory();
        var first = memory.allocateUser(40);
        var middle = memory.allocateUser(16);
        var last = memory.allocateUser(40);
        memory.release(first);
        memory.release(last);
        var program = Collections.<Instruction>nCopies(41, new LoadInstruction(RegisterName.AX));
        var before = snapshotMemory(memory);
        assertProgramLoadFails(new ProgramLoader(), memory, 1, program);
        assertMemoryMatchesSnapshot(memory, before);
        memory.release(middle);
        var pcb = new ProgramLoader().load(memory, 1, program);
        assertEquals(32, pcb.memoryBounds().base());
        assertEquals(41, pcb.memoryBounds().limit());
        assertEquals(0, pcb.programCounter());
        assertProgramStoredAt(memory, 32, program);
        assertEquals(0, memory.allocateKernel(32).base());
        for (int address = 0; address < 32; address++) assertTrue(memory.isEmpty(address));
    }

    private void writeAt(MainMemory memory, int address, Instruction instruction) {
        var gap = memory.allocateUser(address - memory.configuration().userStartAddress());
        var allocation = memory.allocateUser(1);
        memory.writeInstruction(allocation, 0, instruction);
        memory.release(gap);
    }

    private MainMemory createMemory() {
        return new MainMemory(new MemoryConfiguration(128, 32));
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
            MainMemory memory,
            int startAddress,
            List<Instruction> program
    ) {
        for (int index = 0; index < program.size(); index++) {
            assertEquals(
                    program.get(index),
                    assertInstanceOf(InstructionContent.class, memory.read(startAddress + index)).instruction()
            );
        }
    }

    private ProgramLoadException assertProgramLoadFails(
            ProgramLoader loader,
            MainMemory memory,
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

    private List<MemoryContent> snapshotMemory(MainMemory memory) {
        List<MemoryContent> snapshot = new ArrayList<>(memory.size());
        for (int address = 0; address < memory.size(); address++) {
            snapshot.add(memory.read(address));
        }
        return List.copyOf(snapshot);
    }

    private void assertMemoryMatchesSnapshot(
            MainMemory memory,
            List<MemoryContent> expected
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
