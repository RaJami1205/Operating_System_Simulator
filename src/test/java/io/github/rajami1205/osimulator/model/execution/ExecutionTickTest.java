package io.github.rajami1205.osimulator.model.execution;

import io.github.rajami1205.osimulator.model.filesystem.SimulatedFileSystem;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import io.github.rajami1205.osimulator.model.io.KeyboardDevice;

import io.github.rajami1205.osimulator.model.io.ScreenDevice;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.cpu.exception.InvalidRegisterValueException;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionTickTest {
    private final ScreenDevice screen = new ScreenDevice();
    private final SimulatedFileSystem filesystem = new SimulatedFileSystem(new SecondaryStorage(128, 64));
    private final KeyboardDevice keyboard = new KeyboardDevice();
    private final ExecutionEngine engine = new ExecutionEngine();
    private final MainMemory memory = new MainMemory(new MemoryConfiguration(128, 32));
    private final CpuRegisters<Instruction> cpu = new CpuRegisters<>();

    @Test
    void fiveInstructionsConsumeExactlyFiveTicksWithCompleteEffectsAndPersistentIr() {
        var program = List.<Instruction>of(new MovInstruction(RegisterName.AX, 5),
                new LoadInstruction(RegisterName.AX), new AddInstruction(RegisterName.AX),
                new StoreInstruction(RegisterName.BX), new SubInstruction(RegisterName.AX));
        var loaded = new ProgramLoader().loadWithAllocation(memory, 1, program);
        var pcb = loaded.pcb();
        int[] accumulators = {0, 5, 10, 10, 5};
        for (int index = 0; index < program.size(); index++) {
            assertEquals(index == 4 ? TickResult.PROGRAM_FINISHED : TickResult.INSTRUCTION_COMPLETED,
                    engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, loaded.userAllocation()));
            assertSame(program.get(index), cpu.instructionRegister().orElseThrow());
            assertEquals(4097 + index, pcb.programCounter());
            assertEquals(pcb.programCounter(), cpu.programCounter());
            assertEquals(accumulators[index], cpu.accumulator());
            assertEquals(5, cpu.readRegister(RegisterName.AX));
            assertEquals(index >= 3 ? 10 : 0, cpu.readRegister(RegisterName.BX));
            for (int offset = 0; offset < program.size(); offset++)
                assertSame(program.get(offset), memory.readInstruction(pcb.memoryBounds(), offset));
        }
        assertEquals(ProcessState.TERMINATED, pcb.state());
    }

    @Test
    void firstTickErrorPreservesUncommittedValuesAndOriginalCause() {
        var instruction = new AddInstruction(RegisterName.AX);
        var loaded = new ProgramLoader().loadWithAllocation(memory, 1, List.of(instruction));
        var pcb = loaded.pcb();
        cpu.writeAccumulator(32767);
        cpu.writeRegister(RegisterName.AX, 1);
        var failure = assertThrows(ExecutionEngineException.class,
                () -> engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, loaded.userAllocation()));
        assertInstanceOf(InvalidRegisterValueException.class, failure.getCause());
        assertEquals(32767, cpu.accumulator());
        assertEquals(4096, cpu.programCounter());
        assertEquals(4096, pcb.programCounter());
        assertSame(instruction, cpu.instructionRegister().orElseThrow());
    }

    @Test
    void rejectsForeignMemoryAndNonExecutableStateBeforeFetch() {
        var loaded = new ProgramLoader().loadWithAllocation(memory, 1, List.of(new AddInstruction(RegisterName.AX)));
        var pcb = loaded.pcb();
        var before = cpu.snapshot();
        assertThrows(ExecutionEngineException.class, () -> engine.executeTick(filesystem, screen, keyboard,
                new MainMemory(new MemoryConfiguration(128, 32)), cpu, pcb, loaded.userAllocation()));
        for (var state : List.of(ProcessState.BLOCKED, ProcessState.READY_SUSPENDED,
                ProcessState.BLOCKED_SUSPENDED, ProcessState.TERMINATED)) {
            pcb.changeState(state);
            assertThrows(ExecutionEngineException.class,
                    () -> engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, loaded.userAllocation()));
            assertEquals(before, cpu.snapshot());
        }
    }

    @Test
    void statelessEngineExecutesIndependentContextsAndTerminalGuardDoesNotFetch() {
        var firstLoaded = new ProgramLoader().loadWithAllocation(memory, 1, List.of(new LoadInstruction(RegisterName.AX)));
        var first = firstLoaded.pcb();
        var secondLoaded = new ProgramLoader().loadWithAllocation(memory, 2, List.of(new AddInstruction(RegisterName.BX)));
        var second = secondLoaded.pcb();
        assertEquals(4096, first.programCounter());
        assertEquals(first.programCounter(), second.programCounter());
        assertNotEquals(firstLoaded.userAllocation().base(), secondLoaded.userAllocation().base());
        var otherCpu = new CpuRegisters<Instruction>();
        cpu.writeRegister(RegisterName.AX, 7);
        otherCpu.writeRegister(RegisterName.BX, 9);
        assertEquals(TickResult.PROGRAM_FINISHED,
                engine.executeTick(filesystem, screen, keyboard, memory, cpu, first, firstLoaded.userAllocation()));
        assertEquals(TickResult.PROGRAM_FINISHED,
                engine.executeTick(filesystem, screen, keyboard, memory, otherCpu, second, secondLoaded.userAllocation()));
        assertInstanceOf(LoadInstruction.class, cpu.instructionRegister().orElseThrow());
        assertInstanceOf(AddInstruction.class, otherCpu.instructionRegister().orElseThrow());
        assertEquals(7, cpu.accumulator());
        assertEquals(9, otherCpu.accumulator());
        first.changeState(ProcessState.READY);
        var ir = cpu.instructionRegister();
        assertEquals(TickResult.PROGRAM_FINISHED,
                engine.executeTick(filesystem, screen, keyboard, memory, cpu, first, firstLoaded.userAllocation()));
        assertEquals(ir, cpu.instructionRegister());
        assertThrows(NullPointerException.class,
                () -> engine.executeTick(filesystem, screen, keyboard, null, cpu, first, firstLoaded.userAllocation()));
    }
}
