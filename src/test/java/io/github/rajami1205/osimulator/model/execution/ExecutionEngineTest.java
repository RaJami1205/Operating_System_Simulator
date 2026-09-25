package io.github.rajami1205.osimulator.model.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.cpu.exception.InvalidRegisterValueException;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.instruction.AddInstruction;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.MovInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.memory.MemoryContent;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class ExecutionEngineTest {

    private static final int USER_START_ADDRESS = 32;

    @Test
    void shouldRejectNullRequiredArguments() {
        ExecutionEngine engine = new ExecutionEngine();
        MainMemory memory = createMemory();
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, ProcessState.READY);

        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> completeInstruction(engine, null, cpu, pcb)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> completeInstruction(engine, memory, null, pcb)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> completeInstruction(engine, memory, cpu, null)
                )
        );
    }

    @ParameterizedTest
    @EnumSource(value = ProcessState.class, names = {"NEW", "BLOCKED", "READY_SUSPENDED", "BLOCKED_SUSPENDED", "TERMINATED"})
    void shouldRejectNonExecutableStateWithoutChangingMachine(ProcessState state) {
        Instruction instruction = new MovInstruction(RegisterName.AX, 5);
        MainMemory memory = memoryWithProgram(List.of(instruction));
        CpuRegisters<Instruction> cpu = populatedCpu();
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, state);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);

        ExecutionEngineException exception = assertThrows(
                ExecutionEngineException.class,
                () -> completeInstruction(new ExecutionEngine(), memory, cpu, pcb)
        );

        assertValidMessage(exception);
        assertEquals(cpuBeforeExecution, snapshotCpu(cpu));
        assertAll(
                () -> assertEquals(state, pcb.state()),
                () -> assertEquals(0, pcb.programCounter())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessState.class, names = {"NEW", "BLOCKED", "READY_SUSPENDED", "BLOCKED_SUSPENDED", "TERMINATED"})
    void shouldRejectNonExecutableStateEvenAtEndMarkerWithoutChangingMachine(
            ProcessState state
    ) {
        MainMemory memory = createMemory();
        writeAt(memory, 90, new LoadInstruction(RegisterName.DX));
        CpuRegisters<Instruction> cpu = populatedCpu();
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, state);
        pcb.setProgramCounter(pcb.instructionCount());
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);

        ExecutionEngineException exception = assertThrows(
                ExecutionEngineException.class,
                () -> completeInstruction(new ExecutionEngine(), memory, cpu, pcb)
        );

        assertValidMessage(exception);
        assertEquals(cpuBeforeExecution, snapshotCpu(cpu));
        assertAll(
                () -> assertEquals(state, pcb.state()),
                () -> assertEquals(
                        pcb.instructionCount(),
                        pcb.programCounter()
                )
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldRejectProgramStartingInKernelWithoutChangingMachine() {
        MainMemory memory = createMemory();
        CpuRegisters<Instruction> cpu = populatedCpu();
        ProcessControlBlock pcb = createPcb(31, 1, ProcessState.READY);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);

        ExecutionEngineException exception = assertThrows(
                ExecutionEngineException.class,
                () -> completeInstruction(new ExecutionEngine(), memory, cpu, pcb)
        );

        assertValidMessage(exception);
        assertEquals(cpuBeforeExecution, snapshotCpu(cpu));
        assertAll(
                () -> assertEquals(ProcessState.READY, pcb.state()),
                () -> assertEquals(0, pcb.programCounter())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldRejectProgramEndingBeyondMemoryWithoutChangingMachine() {
        MainMemory memory = createMemory();
        Instruction instruction = new LoadInstruction(RegisterName.AX);
        writeAt(memory, 127, instruction);
        CpuRegisters<Instruction> cpu = populatedCpu();
        ProcessControlBlock pcb = createPcb(127, 2, ProcessState.RUNNING);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);

        ExecutionEngineException exception = assertThrows(
                ExecutionEngineException.class,
                () -> completeInstruction(new ExecutionEngine(), memory, cpu, pcb)
        );

        assertValidMessage(exception);
        assertEquals(cpuBeforeExecution, snapshotCpu(cpu));
        assertAll(
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertEquals(0, pcb.programCounter())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldHandleLogicalEndMarkerBeforeAnyMemoryAccess() {
        MainMemory memory = createMemory();
        CpuRegisters<Instruction> cpu = populatedCpu();
        CpuState before = snapshotCpu(cpu);
        // No allocation exists and Base lies beyond physical memory.
        ProcessControlBlock pcb = createPcb(1000, 2, ProcessState.READY);
        pcb.setProgramCounter(2);

        completeInstruction(new ExecutionEngine(), memory, cpu, pcb);

        assertEquals(ProcessState.TERMINATED, pcb.state());
        assertEquals(2, cpu.programCounter());
        assertEquals(2, pcb.programCounter());
        assertCpuDataAndInstructionState(before, cpu);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessState.class, names = {"READY", "RUNNING"})
    void shouldTerminateAtEndMarkerWithoutFetchingOrChangingInstructionRegister(
            ProcessState state
    ) {
        MainMemory memory = createMemory();
        Instruction unrelatedInstruction = new AddInstruction(RegisterName.DX);
        writeAt(memory, 90, unrelatedInstruction);
        CpuRegisters<Instruction> cpu = populatedCpu();
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        ProcessControlBlock pcb = createPcb(
                USER_START_ADDRESS,
                memory.configuration().userPositions(),
                state
        );
        pcb.setProgramCounter(pcb.instructionCount());
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);

        completeInstruction(new ExecutionEngine(), memory, cpu, pcb);

        assertAll(
                () -> assertEquals(pcb.instructionCount(), cpu.programCounter()),
                () -> assertEquals(pcb.instructionCount(), pcb.programCounter()),
                () -> assertEquals(ProcessState.TERMINATED, pcb.state())
        );
        assertCpuDataAndInstructionState(cpuBeforeExecution, cpu);
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessState.class, names = {"READY", "RUNNING"})
    void shouldRejectMissingInstructionWithoutChangingMachine(ProcessState state) {
        MainMemory memory = createMemory();
        memory.allocateUser(2);
        memory.writeInstruction(memory.allocateUser(1), 0, new StoreInstruction(RegisterName.DX));
        CpuRegisters<Instruction> cpu = populatedCpu();
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, state);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);

        ExecutionEngineException exception = assertThrows(
                ExecutionEngineException.class,
                () -> completeInstruction(new ExecutionEngine(), memory, cpu, pcb)
        );

        assertValidMessage(exception);
        assertEquals(cpuBeforeExecution, snapshotCpu(cpu));
        assertAll(
                () -> assertEquals(state, pcb.state()),
                () -> assertEquals(0, pcb.programCounter())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @ParameterizedTest
    @EnumSource(RegisterName.class)
    void shouldExecuteSignedMovForEveryDestinationFromPcbProgramCounter(
            RegisterName destination
    ) {
        Instruction previousInstruction = new StoreInstruction(RegisterName.DX);
        Instruction instruction = new MovInstruction(destination, -5);
        MainMemory memory = memoryWithProgram(List.of(
                instruction,
                new LoadInstruction(RegisterName.AX)
        ));
        CpuRegisters<Instruction> cpu = cpuWithDistinctDataValues(9);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        cpu.setProgramCounter(7);
        cpu.loadInstructionRegister(previousInstruction);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, ProcessState.READY);
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);

        completeInstruction(new ExecutionEngine(), memory, cpu, pcb);

        assertOnlyRegisterChanged(destination, -5, cpuBeforeExecution, cpu);
        assertAll(
                () -> assertEquals(9, cpu.accumulator()),
                () -> assertEquals(1, cpu.programCounter()),
                () -> assertEquals(1, pcb.programCounter()),
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @ParameterizedTest
    @EnumSource(RegisterName.class)
    void shouldExecuteLoadFromEveryRegisterWithoutChangingGeneralRegisters(
            RegisterName source
    ) {
        Instruction instruction = new LoadInstruction(source);
        MainMemory memory = memoryWithProgram(List.of(
                instruction,
                new AddInstruction(RegisterName.AX)
        ));
        CpuRegisters<Instruction> cpu = cpuWithDistinctDataValues(-4);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        int expectedAccumulator = registerValue(cpuBeforeExecution, source);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, ProcessState.RUNNING);
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);

        completeInstruction(new ExecutionEngine(), memory, cpu, pcb);

        assertGeneralRegistersMatchSnapshot(cpuBeforeExecution, cpu);
        assertAll(
                () -> assertEquals(expectedAccumulator, cpu.accumulator()),
                () -> assertEquals(1, cpu.programCounter()),
                () -> assertEquals(1, pcb.programCounter()),
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @ParameterizedTest
    @EnumSource(RegisterName.class)
    void shouldExecuteStoreForEveryDestinationWithoutWritingMemory(
            RegisterName destination
    ) {
        Instruction instruction = new StoreInstruction(destination);
        MainMemory memory = memoryWithProgram(List.of(
                instruction,
                new SubInstruction(RegisterName.DX)
        ));
        CpuRegisters<Instruction> cpu = cpuWithDistinctDataValues(40);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, ProcessState.READY);
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);

        completeInstruction(new ExecutionEngine(), memory, cpu, pcb);

        assertOnlyRegisterChanged(destination, 40, cpuBeforeExecution, cpu);
        assertAll(
                () -> assertEquals(40, cpu.accumulator()),
                () -> assertEquals(1, cpu.programCounter()),
                () -> assertEquals(1, pcb.programCounter()),
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @ParameterizedTest
    @CsvSource({
            "AX, -3, 7",
            "BX, 4, 14",
            "CX, -5, 5",
            "DX, 6, 16"
    })
    void shouldExecuteAddFromEveryRegisterIncludingNegativeSources(
            RegisterName source,
            int sourceValue,
            int expectedAccumulator
    ) {
        Instruction instruction = new AddInstruction(source);
        MainMemory memory = memoryWithProgram(List.of(
                instruction,
                new StoreInstruction(RegisterName.AX)
        ));
        CpuRegisters<Instruction> cpu = cpuWithDistinctDataValues(10);
        cpu.writeRegister(source, sourceValue);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, ProcessState.READY);
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);

        completeInstruction(new ExecutionEngine(), memory, cpu, pcb);

        assertGeneralRegistersMatchSnapshot(cpuBeforeExecution, cpu);
        assertAll(
                () -> assertEquals(expectedAccumulator, cpu.accumulator()),
                () -> assertEquals(1, cpu.programCounter()),
                () -> assertEquals(1, pcb.programCounter()),
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @ParameterizedTest
    @CsvSource({
            "AX, -3, 8",
            "BX, 4, 1",
            "CX, -5, 10",
            "DX, 6, -1"
    })
    void shouldExecuteSubFromEveryRegisterIncludingNegativeSources(
            RegisterName source,
            int sourceValue,
            int expectedAccumulator
    ) {
        Instruction instruction = new SubInstruction(source);
        MainMemory memory = memoryWithProgram(List.of(
                instruction,
                new StoreInstruction(RegisterName.BX)
        ));
        CpuRegisters<Instruction> cpu = cpuWithDistinctDataValues(5);
        cpu.writeRegister(source, sourceValue);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, ProcessState.READY);
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);

        completeInstruction(new ExecutionEngine(), memory, cpu, pcb);

        assertGeneralRegistersMatchSnapshot(cpuBeforeExecution, cpu);
        assertAll(
                () -> assertEquals(expectedAccumulator, cpu.accumulator()),
                () -> assertEquals(1, cpu.programCounter()),
                () -> assertEquals(1, pcb.programCounter()),
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldTerminateAfterSuccessfulFinalInstructionFromRunningState() {
        Instruction firstInstruction = new MovInstruction(RegisterName.AX, 10);
        Instruction finalInstruction = new AddInstruction(RegisterName.AX);
        MainMemory memory = memoryWithProgram(List.of(
                firstInstruction,
                finalInstruction
        ));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(5);
        cpu.writeRegister(RegisterName.AX, 10);
        cpu.loadInstructionRegister(firstInstruction);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, ProcessState.RUNNING);
        pcb.setProgramCounter(1);

        completeInstruction(new ExecutionEngine(), memory, cpu, pcb);

        assertAll(
                () -> assertEquals(15, cpu.accumulator()),
                () -> assertEquals(2, cpu.programCounter()),
                () -> assertEquals(2, pcb.programCounter()),
                () -> assertEquals(ProcessState.TERMINATED, pcb.state()),
                () -> assertSame(finalInstruction, cpu.instructionRegister().orElseThrow())
        );
    }

    @Test
    void shouldTerminateSingleInstructionReadyProcessAfterExecution() {
        Instruction instruction = new MovInstruction(RegisterName.DX, 17);
        MainMemory memory = memoryWithProgram(List.of(instruction));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, ProcessState.READY);

        completeInstruction(new ExecutionEngine(), memory, cpu, pcb);

        assertAll(
                () -> assertEquals(17, cpu.readRegister(RegisterName.DX)),
                () -> assertEquals(1, cpu.programCounter()),
                () -> assertEquals(1, pcb.programCounter()),
                () -> assertEquals(ProcessState.TERMINATED, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
    }

    @Test
    void shouldExecuteExactFitInstructionAtLastMemoryAddress() {
        MainMemory memory = createMemory();
        Instruction instruction = new LoadInstruction(RegisterName.CX);
        writeAt(memory, 127, instruction);
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeRegister(RegisterName.CX, 60);
        ProcessControlBlock pcb = createPcb(127, 1, ProcessState.READY);

        completeInstruction(new ExecutionEngine(), memory, cpu, pcb);

        assertAll(
                () -> assertEquals(60, cpu.accumulator()),
                () -> assertEquals(pcb.instructionCount(), cpu.programCounter()),
                () -> assertEquals(pcb.instructionCount(), pcb.programCounter()),
                () -> assertEquals(ProcessState.TERMINATED, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
    }

    @ParameterizedTest
    @CsvSource({"AX, 32767", "BX, -32768"})
    void shouldExecuteMovAtRegisterBoundaries(RegisterName register, int value) {
        Instruction instruction = new MovInstruction(register, value);
        MainMemory memory = memoryWithProgram(List.of(instruction));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, ProcessState.READY);

        completeInstruction(new ExecutionEngine(), memory, cpu, pcb);

        assertAll(
                () -> assertEquals(value, cpu.readRegister(register)),
                () -> assertEquals(ProcessState.TERMINATED, pcb.state())
        );
    }

    @ParameterizedTest
    @MethodSource("validArithmeticBoundaryCases")
    void shouldCompleteArithmeticAtEveryValidBoundary(
            Instruction instruction,
            int initialAccumulator,
            int sourceValue,
            int expectedAccumulator
    ) {
        MainMemory memory = memoryWithProgram(List.of(instruction));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(initialAccumulator);
        cpu.writeRegister(RegisterName.AX, sourceValue);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, ProcessState.READY);
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);

        completeInstruction(new ExecutionEngine(), memory, cpu, pcb);

        assertAll(
                () -> assertEquals(expectedAccumulator, cpu.accumulator()),
                () -> assertEquals(sourceValue, cpu.readRegister(RegisterName.AX)),
                () -> assertEquals(1, cpu.programCounter()),
                () -> assertEquals(1, pcb.programCounter()),
                () -> assertEquals(ProcessState.TERMINATED, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @ParameterizedTest
    @MethodSource("invalidArithmeticBoundaryCases")
    void shouldWrapEveryInvalidArithmeticBoundaryAndPreserveUncommittedProgress(
            Instruction failingInstruction,
            int initialAccumulator,
            RegisterName source,
            int sourceValue,
            ProcessState initialState
    ) {
        Instruction previousInstruction = new MovInstruction(RegisterName.BX, 4);
        MainMemory memory = memoryWithProgram(List.of(failingInstruction));
        CpuRegisters<Instruction> cpu = cpuWithDistinctDataValues(initialAccumulator);
        cpu.writeRegister(source, sourceValue);
        cpu.setProgramCounter(75);
        cpu.loadInstructionRegister(previousInstruction);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, initialState);
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);

        ExecutionEngineException exception = assertThrows(
                ExecutionEngineException.class,
                () -> completeInstruction(new ExecutionEngine(), memory, cpu, pcb)
        );

        assertAll(
                () -> assertValidMessage(exception),
                () -> assertInstanceOf(
                        InvalidRegisterValueException.class,
                        exception.getCause()
                ),
                () -> assertEquals(initialAccumulator, cpu.accumulator()),
                () -> assertEquals(0, cpu.programCounter()),
                () -> assertEquals(0, pcb.programCounter()),
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertSame(
                        failingInstruction,
                        cpu.instructionRegister().orElseThrow()
                )
        );
        assertGeneralRegistersMatchSnapshot(cpuBeforeExecution, cpu);
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldPreserveRepresentativeProgramSemanticsThroughTicks() {
        List<Instruction> program = List.of(
                new MovInstruction(RegisterName.AX, 5),
                new LoadInstruction(RegisterName.AX),
                new AddInstruction(RegisterName.AX),
                new StoreInstruction(RegisterName.BX),
                new SubInstruction(RegisterName.AX)
        );
        MainMemory memory = memoryWithProgram(program);
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        ProcessControlBlock pcb = createPcb(
                USER_START_ADDRESS,
                program.size(),
                ProcessState.READY
        );
        List<MemoryContent> memoryBeforeExecution = snapshotMemory(memory);
        ExecutionEngine engine = new ExecutionEngine();

        completeInstruction(engine, memory, cpu, pcb);
        assertStep(cpu, pcb, program.get(0), 1, ProcessState.RUNNING);
        assertEquals(5, cpu.readRegister(RegisterName.AX));

        completeInstruction(engine, memory, cpu, pcb);
        assertStep(cpu, pcb, program.get(1), 2, ProcessState.RUNNING);
        assertEquals(5, cpu.accumulator());

        completeInstruction(engine, memory, cpu, pcb);
        assertStep(cpu, pcb, program.get(2), 3, ProcessState.RUNNING);
        assertEquals(10, cpu.accumulator());

        completeInstruction(engine, memory, cpu, pcb);
        assertStep(cpu, pcb, program.get(3), 4, ProcessState.RUNNING);
        assertEquals(10, cpu.readRegister(RegisterName.BX));

        completeInstruction(engine, memory, cpu, pcb);
        assertStep(cpu, pcb, program.get(4), 5, ProcessState.TERMINATED);
        assertAll(
                () -> assertEquals(5, cpu.accumulator()),
                () -> assertEquals(5, cpu.readRegister(RegisterName.AX)),
                () -> assertEquals(10, cpu.readRegister(RegisterName.BX))
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldReuseEngineAcrossIndependentMachines() {
        ExecutionEngine engine = new ExecutionEngine();
        Instruction firstInstruction = new MovInstruction(RegisterName.AX, 7);
        MainMemory firstMemory = memoryWithProgram(List.of(firstInstruction));
        CpuRegisters<Instruction> firstCpu = new CpuRegisters<>();
        ProcessControlBlock firstPcb = createPcb(
                USER_START_ADDRESS,
                1,
                ProcessState.READY
        );
        Instruction secondInstruction = new LoadInstruction(RegisterName.BX);
        MainMemory secondMemory = memoryWithProgram(List.of(secondInstruction));
        CpuRegisters<Instruction> secondCpu = new CpuRegisters<>();
        secondCpu.writeRegister(RegisterName.BX, 9);
        ProcessControlBlock secondPcb = createPcb(
                USER_START_ADDRESS,
                1,
                ProcessState.READY
        );

        completeInstruction(engine, firstMemory, firstCpu, firstPcb);
        completeInstruction(engine, secondMemory, secondCpu, secondPcb);

        assertAll(
                () -> assertEquals(7, firstCpu.readRegister(RegisterName.AX)),
                () -> assertEquals(0, firstCpu.accumulator()),
                () -> assertSame(
                        firstInstruction,
                        firstCpu.instructionRegister().orElseThrow()
                ),
                () -> assertEquals(0, secondCpu.readRegister(RegisterName.AX)),
                () -> assertEquals(9, secondCpu.accumulator()),
                () -> assertSame(
                        secondInstruction,
                        secondCpu.instructionRegister().orElseThrow()
                ),
                () -> assertEquals(ProcessState.TERMINATED, firstPcb.state()),
                () -> assertEquals(ProcessState.TERMINATED, secondPcb.state())
        );
    }

    @Test
    void shouldExecuteIndependentMachineAfterFailureOnSameEngine() {
        ExecutionEngine engine = new ExecutionEngine();
        Instruction failingInstruction = new AddInstruction(RegisterName.AX);
        MainMemory failingMemory = memoryWithProgram(List.of(failingInstruction));
        CpuRegisters<Instruction> failingCpu = new CpuRegisters<>();
        failingCpu.writeAccumulator(32767);
        failingCpu.writeRegister(RegisterName.AX, 1);
        ProcessControlBlock failingPcb = createPcb(
                USER_START_ADDRESS,
                1,
                ProcessState.READY
        );
        Instruction successfulInstruction = new MovInstruction(RegisterName.CX, -6);
        MainMemory successfulMemory = memoryWithProgram(
                List.of(successfulInstruction)
        );
        CpuRegisters<Instruction> successfulCpu = new CpuRegisters<>();
        ProcessControlBlock successfulPcb = createPcb(
                USER_START_ADDRESS,
                1,
                ProcessState.READY
        );

        assertThrows(
                ExecutionEngineException.class,
                () -> completeInstruction(engine, failingMemory, failingCpu, failingPcb)
        );
        CpuState failingCpuAfterFailure = snapshotCpu(failingCpu);
        ProcessState failingStateAfterFailure = failingPcb.state();
        int failingPcbProgramCounterAfterFailure = failingPcb.programCounter();

        completeInstruction(engine, successfulMemory, successfulCpu, successfulPcb);

        assertAll(
                () -> assertEquals(-6, successfulCpu.readRegister(RegisterName.CX)),
                () -> assertEquals(1, successfulCpu.programCounter()),
                () -> assertEquals(1, successfulPcb.programCounter()),
                () -> assertEquals(ProcessState.TERMINATED, successfulPcb.state()),
                () -> assertSame(
                        successfulInstruction,
                        successfulCpu.instructionRegister().orElseThrow()
                ),
                () -> assertEquals(failingCpuAfterFailure, snapshotCpu(failingCpu)),
                () -> assertEquals(failingStateAfterFailure, failingPcb.state()),
                () -> assertEquals(
                        failingPcbProgramCounterAfterFailure,
                        failingPcb.programCounter()
                )
        );
    }

    private static Stream<Arguments> validArithmeticBoundaryCases() {
        return Stream.of(
                Arguments.of(new AddInstruction(RegisterName.AX), 32766, 1, 32767),
                Arguments.of(new AddInstruction(RegisterName.AX), -32767, -1, -32768),
                Arguments.of(new SubInstruction(RegisterName.AX), -32767, 1, -32768),
                Arguments.of(new SubInstruction(RegisterName.AX), 32766, -1, 32767)
        );
    }

    private static Stream<Arguments> invalidArithmeticBoundaryCases() {
        return Stream.of(
                Arguments.of(
                        new AddInstruction(RegisterName.AX),
                        32767,
                        RegisterName.AX,
                        1,
                        ProcessState.READY
                ),
                Arguments.of(
                        new AddInstruction(RegisterName.BX),
                        -32768,
                        RegisterName.BX,
                        -1,
                        ProcessState.RUNNING
                ),
                Arguments.of(
                        new SubInstruction(RegisterName.CX),
                        -32768,
                        RegisterName.CX,
                        1,
                        ProcessState.RUNNING
                ),
                Arguments.of(
                        new SubInstruction(RegisterName.DX),
                        32767,
                        RegisterName.DX,
                        -1,
                        ProcessState.READY
                )
        );
    }

    @Test
    void shouldStopAtLogicalLimitWithoutExecutingAdjacentAllocationOrReleasingProgram() {
        var memory = createMemory();
        var first = memory.allocateUser(1);
        var next = memory.allocateUser(1);
        var instruction = new MovInstruction(RegisterName.AX, 7);
        memory.writeInstruction(first, 0, instruction);
        memory.writeInstruction(next, 0, new MovInstruction(RegisterName.AX, 99));
        var pcb = createPcb(first.base(), first.size(), ProcessState.READY);
        var cpu = new CpuRegisters<Instruction>();
        var engine = new ExecutionEngine();
        completeInstruction(engine, memory, cpu, pcb);
        assertEquals(7, cpu.readRegister(RegisterName.AX));
        assertEquals(1, cpu.programCounter());
        assertEquals(1, pcb.programCounter());
        assertEquals(ProcessState.TERMINATED, pcb.state());
        assertSame(instruction, memory.readInstruction(pcb.memoryBounds(), 0));
        assertEquals(34, memory.allocateUser(1).base());
        // A restored terminal marker must not fetch the following program.
        pcb.changeState(ProcessState.READY);
        completeInstruction(engine, memory, cpu, pcb);
        assertEquals(7, cpu.readRegister(RegisterName.AX));
        assertSame(instruction, cpu.instructionRegister().orElseThrow());
        assertEquals(ProcessState.TERMINATED, pcb.state());
    }

    // Semantic regression helper: completes an instruction through the public tick API only.
    private void completeInstruction(ExecutionEngine engine, MainMemory memory, CpuRegisters<Instruction> cpu, ProcessControlBlock pcb) {
        var progress = new ExecutionProgress();
        while (engine.executeTick(memory, cpu, pcb, progress) == TickResult.IN_PROGRESS) { }
    }

    private void writeAt(MainMemory memory, int address, Instruction instruction) {
        var gap = memory.allocateUser(address - USER_START_ADDRESS);
        var allocation = memory.allocateUser(1);
        memory.writeInstruction(allocation, 0, instruction);
        memory.release(gap);
    }

    private MainMemory createMemory() {
        return new MainMemory(new MemoryConfiguration(128, USER_START_ADDRESS));
    }

    private MainMemory memoryWithProgram(List<Instruction> instructions) {
        MainMemory memory = createMemory();
        memory.writeUserBlock(memory.allocateUser(instructions.size()), instructions);
        return memory;
    }

    private ProcessControlBlock createPcb(
            int programStartAddress,
            int instructionCount,
            ProcessState state
    ) {
        ProcessControlBlock pcb = new ProcessControlBlock(
                1,
                programStartAddress,
                instructionCount
        );
        pcb.setProgramCounter(0);
        pcb.changeState(state);
        return pcb;
    }

    private CpuRegisters<Instruction> populatedCpu() {
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(20);
        cpu.writeRegister(RegisterName.AX, 10);
        cpu.writeRegister(RegisterName.BX, -20);
        cpu.writeRegister(RegisterName.CX, 30);
        cpu.writeRegister(RegisterName.DX, -40);
        cpu.setProgramCounter(90);
        cpu.loadInstructionRegister(new StoreInstruction(RegisterName.CX));
        return cpu;
    }

    private CpuRegisters<Instruction> cpuWithDistinctDataValues(int accumulator) {
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(accumulator);
        cpu.writeRegister(RegisterName.AX, 11);
        cpu.writeRegister(RegisterName.BX, -12);
        cpu.writeRegister(RegisterName.CX, 13);
        cpu.writeRegister(RegisterName.DX, -14);
        return cpu;
    }

    private CpuState snapshotCpu(CpuRegisters<Instruction> cpu) {
        return new CpuState(
                cpu.accumulator(),
                cpu.readRegister(RegisterName.AX),
                cpu.readRegister(RegisterName.BX),
                cpu.readRegister(RegisterName.CX),
                cpu.readRegister(RegisterName.DX),
                cpu.programCounter(),
                cpu.instructionRegister()
        );
    }

    private void assertCpuDataAndInstructionState(
            CpuState expected,
            CpuRegisters<Instruction> actual
    ) {
        assertAll(
                () -> assertEquals(expected.accumulator(), actual.accumulator()),
                () -> assertEquals(expected.ax(), actual.readRegister(RegisterName.AX)),
                () -> assertEquals(expected.bx(), actual.readRegister(RegisterName.BX)),
                () -> assertEquals(expected.cx(), actual.readRegister(RegisterName.CX)),
                () -> assertEquals(expected.dx(), actual.readRegister(RegisterName.DX)),
                () -> assertEquals(expected.instructionRegister(), actual.instructionRegister())
        );
    }

    private void assertOnlyRegisterChanged(
            RegisterName destination,
            int expectedValue,
            CpuState previousState,
            CpuRegisters<Instruction> actual
    ) {
        for (RegisterName register : RegisterName.values()) {
            int expectedRegisterValue = register == destination
                    ? expectedValue
                    : registerValue(previousState, register);
            assertEquals(expectedRegisterValue, actual.readRegister(register));
        }
    }

    private void assertGeneralRegistersMatchSnapshot(
            CpuState expected,
            CpuRegisters<Instruction> actual
    ) {
        for (RegisterName register : RegisterName.values()) {
            assertEquals(
                    registerValue(expected, register),
                    actual.readRegister(register),
                    "Unexpected change in register " + register
            );
        }
    }

    private int registerValue(CpuState state, RegisterName register) {
        return switch (register) {
            case AX -> state.ax();
            case BX -> state.bx();
            case CX -> state.cx();
            case DX -> state.dx();
        };
    }

    private void assertStep(
            CpuRegisters<Instruction> cpu,
            ProcessControlBlock pcb,
            Instruction expectedInstruction,
            int expectedProgramCounter,
            ProcessState expectedState
    ) {
        assertAll(
                () -> assertEquals(expectedProgramCounter, cpu.programCounter()),
                () -> assertEquals(expectedProgramCounter, pcb.programCounter()),
                () -> assertEquals(expectedState, pcb.state()),
                () -> assertSame(
                        expectedInstruction,
                        cpu.instructionRegister().orElseThrow()
                )
        );
    }

    private void assertValidMessage(ExecutionEngineException exception) {
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

    private record CpuState(
            int accumulator,
            int ax,
            int bx,
            int cx,
            int dx,
            int programCounter,
            Optional<Instruction> instructionRegister
    ) {
    }
}
