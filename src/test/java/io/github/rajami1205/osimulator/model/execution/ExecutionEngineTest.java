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
import io.github.rajami1205.osimulator.model.memory.Memory;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class ExecutionEngineTest {

    private static final int USER_START_ADDRESS = 32;

    @Test
    void shouldRejectNullRequiredArguments() {
        ExecutionEngine engine = new ExecutionEngine();
        Memory<Instruction> memory = createMemory();
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, ProcessState.READY);

        assertAll(
                () -> assertThrows(
                        NullPointerException.class,
                        () -> engine.executeNext(null, cpu, pcb)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> engine.executeNext(memory, null, pcb)
                ),
                () -> assertThrows(
                        NullPointerException.class,
                        () -> engine.executeNext(memory, cpu, null)
                )
        );
    }

    @ParameterizedTest
    @EnumSource(value = ProcessState.class, names = {"NEW", "BLOCKED", "TERMINATED"})
    void shouldRejectNonExecutableStateWithoutChangingMachine(ProcessState state) {
        Instruction instruction = new MovInstruction(RegisterName.AX, 5);
        Memory<Instruction> memory = memoryWithProgram(List.of(instruction));
        CpuRegisters<Instruction> cpu = populatedCpu();
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, state);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        List<Optional<Instruction>> memoryBeforeExecution = snapshotMemory(memory);

        ExecutionEngineException exception = assertThrows(
                ExecutionEngineException.class,
                () -> new ExecutionEngine().executeNext(memory, cpu, pcb)
        );

        assertValidMessage(exception);
        assertEquals(cpuBeforeExecution, snapshotCpu(cpu));
        assertAll(
                () -> assertEquals(state, pcb.state()),
                () -> assertEquals(USER_START_ADDRESS, pcb.programCounter())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldRejectProgramStartingInKernelWithoutChangingMachine() {
        Memory<Instruction> memory = createMemory();
        CpuRegisters<Instruction> cpu = populatedCpu();
        ProcessControlBlock pcb = createPcb(31, 1, ProcessState.READY);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        List<Optional<Instruction>> memoryBeforeExecution = snapshotMemory(memory);

        ExecutionEngineException exception = assertThrows(
                ExecutionEngineException.class,
                () -> new ExecutionEngine().executeNext(memory, cpu, pcb)
        );

        assertValidMessage(exception);
        assertEquals(cpuBeforeExecution, snapshotCpu(cpu));
        assertAll(
                () -> assertEquals(ProcessState.READY, pcb.state()),
                () -> assertEquals(31, pcb.programCounter())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldRejectProgramEndingBeyondMemoryWithoutChangingMachine() {
        Memory<Instruction> memory = createMemory();
        Instruction instruction = new LoadInstruction(RegisterName.AX);
        memory.writeUser(127, instruction);
        CpuRegisters<Instruction> cpu = populatedCpu();
        ProcessControlBlock pcb = createPcb(127, 2, ProcessState.RUNNING);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        List<Optional<Instruction>> memoryBeforeExecution = snapshotMemory(memory);

        ExecutionEngineException exception = assertThrows(
                ExecutionEngineException.class,
                () -> new ExecutionEngine().executeNext(memory, cpu, pcb)
        );

        assertValidMessage(exception);
        assertEquals(cpuBeforeExecution, snapshotCpu(cpu));
        assertAll(
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertEquals(127, pcb.programCounter())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @ParameterizedTest
    @EnumSource(value = ProcessState.class, names = {"READY", "RUNNING"})
    void shouldTerminateAtEndMarkerWithoutFetchingOrChangingInstructionRegister(
            ProcessState state
    ) {
        Memory<Instruction> memory = createMemory();
        Instruction unrelatedInstruction = new AddInstruction(RegisterName.DX);
        memory.writeUser(90, unrelatedInstruction);
        CpuRegisters<Instruction> cpu = populatedCpu();
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        ProcessControlBlock pcb = createPcb(
                USER_START_ADDRESS,
                memory.configuration().userPositions(),
                state
        );
        pcb.setProgramCounter(memory.size());
        List<Optional<Instruction>> memoryBeforeExecution = snapshotMemory(memory);

        new ExecutionEngine().executeNext(memory, cpu, pcb);

        assertAll(
                () -> assertEquals(memory.size(), cpu.programCounter()),
                () -> assertEquals(memory.size(), pcb.programCounter()),
                () -> assertEquals(ProcessState.TERMINATED, pcb.state())
        );
        assertCpuDataAndInstructionState(cpuBeforeExecution, cpu);
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldRejectMissingInstructionWithoutChangingMachine() {
        Memory<Instruction> memory = createMemory();
        memory.writeUser(90, new StoreInstruction(RegisterName.DX));
        CpuRegisters<Instruction> cpu = populatedCpu();
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, ProcessState.READY);
        CpuState cpuBeforeExecution = snapshotCpu(cpu);
        List<Optional<Instruction>> memoryBeforeExecution = snapshotMemory(memory);

        ExecutionEngineException exception = assertThrows(
                ExecutionEngineException.class,
                () -> new ExecutionEngine().executeNext(memory, cpu, pcb)
        );

        assertValidMessage(exception);
        assertEquals(cpuBeforeExecution, snapshotCpu(cpu));
        assertAll(
                () -> assertEquals(ProcessState.READY, pcb.state()),
                () -> assertEquals(USER_START_ADDRESS, pcb.programCounter())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldExecuteMovAndSynchronizeProgramCountersFromPcb() {
        Instruction instruction = new MovInstruction(RegisterName.AX, 5);
        Memory<Instruction> memory = memoryWithProgram(List.of(
                instruction,
                new LoadInstruction(RegisterName.AX)
        ));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(9);
        cpu.writeRegister(RegisterName.BX, 11);
        cpu.writeRegister(RegisterName.CX, 12);
        cpu.writeRegister(RegisterName.DX, 13);
        cpu.setProgramCounter(7);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, ProcessState.READY);
        List<Optional<Instruction>> memoryBeforeExecution = snapshotMemory(memory);

        new ExecutionEngine().executeNext(memory, cpu, pcb);

        assertAll(
                () -> assertEquals(5, cpu.readRegister(RegisterName.AX)),
                () -> assertEquals(11, cpu.readRegister(RegisterName.BX)),
                () -> assertEquals(12, cpu.readRegister(RegisterName.CX)),
                () -> assertEquals(13, cpu.readRegister(RegisterName.DX)),
                () -> assertEquals(9, cpu.accumulator()),
                () -> assertEquals(33, cpu.programCounter()),
                () -> assertEquals(33, pcb.programCounter()),
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldExecuteLoadUsingRegisterAsAccumulatorSource() {
        Instruction instruction = new LoadInstruction(RegisterName.BX);
        Memory<Instruction> memory = memoryWithProgram(List.of(
                instruction,
                new AddInstruction(RegisterName.AX)
        ));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(-4);
        cpu.writeRegister(RegisterName.BX, 25);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, ProcessState.RUNNING);
        List<Optional<Instruction>> memoryBeforeExecution = snapshotMemory(memory);

        new ExecutionEngine().executeNext(memory, cpu, pcb);

        assertAll(
                () -> assertEquals(25, cpu.accumulator()),
                () -> assertEquals(25, cpu.readRegister(RegisterName.BX)),
                () -> assertEquals(33, cpu.programCounter()),
                () -> assertEquals(33, pcb.programCounter()),
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldExecuteStoreUsingAccumulatorAsRegisterSourceWithoutWritingMemory() {
        Instruction instruction = new StoreInstruction(RegisterName.CX);
        Memory<Instruction> memory = memoryWithProgram(List.of(
                instruction,
                new SubInstruction(RegisterName.DX)
        ));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(40);
        cpu.writeRegister(RegisterName.CX, -10);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, ProcessState.READY);
        List<Optional<Instruction>> memoryBeforeExecution = snapshotMemory(memory);

        new ExecutionEngine().executeNext(memory, cpu, pcb);

        assertAll(
                () -> assertEquals(40, cpu.readRegister(RegisterName.CX)),
                () -> assertEquals(40, cpu.accumulator()),
                () -> assertEquals(33, cpu.programCounter()),
                () -> assertEquals(33, pcb.programCounter()),
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldExecuteAddWithoutChangingSourceRegister() {
        Instruction instruction = new AddInstruction(RegisterName.DX);
        Memory<Instruction> memory = memoryWithProgram(List.of(
                instruction,
                new StoreInstruction(RegisterName.AX)
        ));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(20);
        cpu.writeRegister(RegisterName.DX, -7);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, ProcessState.READY);

        new ExecutionEngine().executeNext(memory, cpu, pcb);

        assertAll(
                () -> assertEquals(13, cpu.accumulator()),
                () -> assertEquals(-7, cpu.readRegister(RegisterName.DX)),
                () -> assertEquals(33, cpu.programCounter()),
                () -> assertEquals(33, pcb.programCounter()),
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
    }

    @Test
    void shouldExecuteSubWithoutChangingSourceRegister() {
        Instruction instruction = new SubInstruction(RegisterName.AX);
        Memory<Instruction> memory = memoryWithProgram(List.of(
                instruction,
                new StoreInstruction(RegisterName.BX)
        ));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(20);
        cpu.writeRegister(RegisterName.AX, -7);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, ProcessState.READY);

        new ExecutionEngine().executeNext(memory, cpu, pcb);

        assertAll(
                () -> assertEquals(27, cpu.accumulator()),
                () -> assertEquals(-7, cpu.readRegister(RegisterName.AX)),
                () -> assertEquals(33, cpu.programCounter()),
                () -> assertEquals(33, pcb.programCounter()),
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
    }

    @Test
    void shouldTerminateAfterSuccessfulFinalInstructionFromRunningState() {
        Instruction firstInstruction = new MovInstruction(RegisterName.AX, 10);
        Instruction finalInstruction = new AddInstruction(RegisterName.AX);
        Memory<Instruction> memory = memoryWithProgram(List.of(
                firstInstruction,
                finalInstruction
        ));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(5);
        cpu.writeRegister(RegisterName.AX, 10);
        cpu.loadInstructionRegister(firstInstruction);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 2, ProcessState.RUNNING);
        pcb.setProgramCounter(33);

        new ExecutionEngine().executeNext(memory, cpu, pcb);

        assertAll(
                () -> assertEquals(15, cpu.accumulator()),
                () -> assertEquals(34, cpu.programCounter()),
                () -> assertEquals(34, pcb.programCounter()),
                () -> assertEquals(ProcessState.TERMINATED, pcb.state()),
                () -> assertSame(finalInstruction, cpu.instructionRegister().orElseThrow())
        );
    }

    @Test
    void shouldTerminateSingleInstructionReadyProcessAfterExecution() {
        Instruction instruction = new MovInstruction(RegisterName.DX, 17);
        Memory<Instruction> memory = memoryWithProgram(List.of(instruction));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, ProcessState.READY);

        new ExecutionEngine().executeNext(memory, cpu, pcb);

        assertAll(
                () -> assertEquals(17, cpu.readRegister(RegisterName.DX)),
                () -> assertEquals(33, cpu.programCounter()),
                () -> assertEquals(33, pcb.programCounter()),
                () -> assertEquals(ProcessState.TERMINATED, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
    }

    @Test
    void shouldExecuteExactFitInstructionAtLastMemoryAddress() {
        Memory<Instruction> memory = createMemory();
        Instruction instruction = new LoadInstruction(RegisterName.CX);
        memory.writeUser(127, instruction);
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeRegister(RegisterName.CX, 60);
        ProcessControlBlock pcb = createPcb(127, 1, ProcessState.READY);

        new ExecutionEngine().executeNext(memory, cpu, pcb);

        assertAll(
                () -> assertEquals(60, cpu.accumulator()),
                () -> assertEquals(memory.size(), cpu.programCounter()),
                () -> assertEquals(memory.size(), pcb.programCounter()),
                () -> assertEquals(ProcessState.TERMINATED, pcb.state()),
                () -> assertSame(instruction, cpu.instructionRegister().orElseThrow())
        );
    }

    @ParameterizedTest
    @CsvSource({"AX, 127", "BX, -127"})
    void shouldExecuteMovAtRegisterBoundaries(RegisterName register, int value) {
        Instruction instruction = new MovInstruction(register, value);
        Memory<Instruction> memory = memoryWithProgram(List.of(instruction));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, ProcessState.READY);

        new ExecutionEngine().executeNext(memory, cpu, pcb);

        assertAll(
                () -> assertEquals(value, cpu.readRegister(register)),
                () -> assertEquals(ProcessState.TERMINATED, pcb.state())
        );
    }

    @Test
    void shouldAcceptMaximumValidAddResult() {
        Instruction instruction = new AddInstruction(RegisterName.AX);
        Memory<Instruction> memory = memoryWithProgram(List.of(instruction));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(126);
        cpu.writeRegister(RegisterName.AX, 1);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, ProcessState.READY);

        new ExecutionEngine().executeNext(memory, cpu, pcb);

        assertEquals(127, cpu.accumulator());
    }

    @Test
    void shouldAcceptMinimumValidSubResult() {
        Instruction instruction = new SubInstruction(RegisterName.AX);
        Memory<Instruction> memory = memoryWithProgram(List.of(instruction));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(-126);
        cpu.writeRegister(RegisterName.AX, 1);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, ProcessState.READY);

        new ExecutionEngine().executeNext(memory, cpu, pcb);

        assertEquals(-127, cpu.accumulator());
    }

    @Test
    void shouldWrapInvalidAddResultAndPreserveUncommittedProgress() {
        Instruction previousInstruction = new MovInstruction(RegisterName.BX, 4);
        Instruction failingInstruction = new AddInstruction(RegisterName.AX);
        Memory<Instruction> memory = memoryWithProgram(List.of(failingInstruction));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(127);
        cpu.writeRegister(RegisterName.AX, 1);
        cpu.writeRegister(RegisterName.BX, 4);
        cpu.setProgramCounter(75);
        cpu.loadInstructionRegister(previousInstruction);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, ProcessState.READY);
        List<Optional<Instruction>> memoryBeforeExecution = snapshotMemory(memory);

        ExecutionEngineException exception = assertThrows(
                ExecutionEngineException.class,
                () -> new ExecutionEngine().executeNext(memory, cpu, pcb)
        );

        assertAll(
                () -> assertValidMessage(exception),
                () -> assertInstanceOf(
                        InvalidRegisterValueException.class,
                        exception.getCause()
                ),
                () -> assertEquals(127, cpu.accumulator()),
                () -> assertEquals(1, cpu.readRegister(RegisterName.AX)),
                () -> assertEquals(4, cpu.readRegister(RegisterName.BX)),
                () -> assertEquals(USER_START_ADDRESS, cpu.programCounter()),
                () -> assertEquals(USER_START_ADDRESS, pcb.programCounter()),
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertSame(
                        failingInstruction,
                        cpu.instructionRegister().orElseThrow()
                )
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldWrapInvalidSubResultAndPreserveUncommittedProgress() {
        Instruction failingInstruction = new SubInstruction(RegisterName.DX);
        Memory<Instruction> memory = memoryWithProgram(List.of(failingInstruction));
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        cpu.writeAccumulator(-127);
        cpu.writeRegister(RegisterName.DX, 1);
        cpu.setProgramCounter(75);
        ProcessControlBlock pcb = createPcb(USER_START_ADDRESS, 1, ProcessState.RUNNING);
        List<Optional<Instruction>> memoryBeforeExecution = snapshotMemory(memory);

        ExecutionEngineException exception = assertThrows(
                ExecutionEngineException.class,
                () -> new ExecutionEngine().executeNext(memory, cpu, pcb)
        );

        assertAll(
                () -> assertValidMessage(exception),
                () -> assertInstanceOf(
                        InvalidRegisterValueException.class,
                        exception.getCause()
                ),
                () -> assertEquals(-127, cpu.accumulator()),
                () -> assertEquals(1, cpu.readRegister(RegisterName.DX)),
                () -> assertEquals(USER_START_ADDRESS, cpu.programCounter()),
                () -> assertEquals(USER_START_ADDRESS, pcb.programCounter()),
                () -> assertEquals(ProcessState.RUNNING, pcb.state()),
                () -> assertSame(
                        failingInstruction,
                        cpu.instructionRegister().orElseThrow()
                )
        );
        assertMemoryMatchesSnapshot(memory, memoryBeforeExecution);
    }

    @Test
    void shouldExecuteRepresentativeProgramOneStepAtATime() {
        List<Instruction> program = List.of(
                new MovInstruction(RegisterName.AX, 5),
                new LoadInstruction(RegisterName.AX),
                new AddInstruction(RegisterName.AX),
                new StoreInstruction(RegisterName.BX),
                new SubInstruction(RegisterName.AX)
        );
        Memory<Instruction> memory = memoryWithProgram(program);
        CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        ProcessControlBlock pcb = createPcb(
                USER_START_ADDRESS,
                program.size(),
                ProcessState.READY
        );
        List<Optional<Instruction>> memoryBeforeExecution = snapshotMemory(memory);
        ExecutionEngine engine = new ExecutionEngine();

        engine.executeNext(memory, cpu, pcb);
        assertStep(cpu, pcb, program.get(0), 33, ProcessState.RUNNING);
        assertEquals(5, cpu.readRegister(RegisterName.AX));

        engine.executeNext(memory, cpu, pcb);
        assertStep(cpu, pcb, program.get(1), 34, ProcessState.RUNNING);
        assertEquals(5, cpu.accumulator());

        engine.executeNext(memory, cpu, pcb);
        assertStep(cpu, pcb, program.get(2), 35, ProcessState.RUNNING);
        assertEquals(10, cpu.accumulator());

        engine.executeNext(memory, cpu, pcb);
        assertStep(cpu, pcb, program.get(3), 36, ProcessState.RUNNING);
        assertEquals(10, cpu.readRegister(RegisterName.BX));

        engine.executeNext(memory, cpu, pcb);
        assertStep(cpu, pcb, program.get(4), 37, ProcessState.TERMINATED);
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
        Memory<Instruction> firstMemory = memoryWithProgram(List.of(firstInstruction));
        CpuRegisters<Instruction> firstCpu = new CpuRegisters<>();
        ProcessControlBlock firstPcb = createPcb(
                USER_START_ADDRESS,
                1,
                ProcessState.READY
        );
        Instruction secondInstruction = new LoadInstruction(RegisterName.BX);
        Memory<Instruction> secondMemory = memoryWithProgram(List.of(secondInstruction));
        CpuRegisters<Instruction> secondCpu = new CpuRegisters<>();
        secondCpu.writeRegister(RegisterName.BX, 9);
        ProcessControlBlock secondPcb = createPcb(
                USER_START_ADDRESS,
                1,
                ProcessState.READY
        );

        engine.executeNext(firstMemory, firstCpu, firstPcb);
        engine.executeNext(secondMemory, secondCpu, secondPcb);

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

    private Memory<Instruction> createMemory() {
        return new Memory<>(new MemoryConfiguration(128, USER_START_ADDRESS));
    }

    private Memory<Instruction> memoryWithProgram(List<Instruction> instructions) {
        Memory<Instruction> memory = createMemory();
        memory.writeUserBlock(USER_START_ADDRESS, instructions);
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
