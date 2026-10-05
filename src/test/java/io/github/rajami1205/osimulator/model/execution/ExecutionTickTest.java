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
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionTickTest {
    private final ScreenDevice screen = new ScreenDevice();
    private final SimulatedFileSystem filesystem = new SimulatedFileSystem(new SecondaryStorage(128, 64));
    private final KeyboardDevice keyboard = new KeyboardDevice();
    private final ExecutionEngine engine = new ExecutionEngine();
    private final MainMemory memory = new MainMemory(new MemoryConfiguration(128, 32));
    private final CpuRegisters<Instruction> cpu = new CpuRegisters<>();
    private final ExecutionProgress progress = new ExecutionProgress();

    @Test
    void fiveInstructionsConsumeExactlyElevenTicksWithAtomicEffectsAndPersistentIr() {
        var program = List.<Instruction>of(new MovInstruction(RegisterName.AX, 5),
                new LoadInstruction(RegisterName.AX), new AddInstruction(RegisterName.AX),
                new StoreInstruction(RegisterName.BX), new SubInstruction(RegisterName.AX));
        var pcbLoaded = new ProgramLoader().loadWithAllocation(memory, 1, program);
        var pcb = pcbLoaded.pcb();
        assertTrue(progress.isIdle());
        assertTrue(progress.instruction().isEmpty());
        assertEquals(0, progress.consumedTicks());
        int calls = 0;
        for (int pc = 0; pc < program.size(); pc++) {
            var instruction = program.get(pc);
            var before = cpu.snapshot();
            int weight = instruction.executionWeight().ticks();
            for (int tick = 1; tick <= weight; tick++) {
                var result = engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, progress, pcbLoaded.userAllocation());
                calls++;
                assertSame(instruction, cpu.instructionRegister().orElseThrow());
                if (tick < weight) {
                    assertEquals(TickResult.IN_PROGRESS, result);
                    assertEquals(pc, pcb.programCounter());
                    assertEquals(pc, cpu.programCounter());
                    assertEquals(before.accumulator(), cpu.accumulator());
                    assertEquals(before.ax(), cpu.readRegister(RegisterName.AX));
                    assertEquals(before.bx(), cpu.readRegister(RegisterName.BX));
                    assertEquals(ProcessState.RUNNING, pcb.state());
                    assertEquals(tick, progress.consumedTicks());
                    assertEquals(Optional.of(instruction), progress.instruction());
                } else {
                    assertEquals(pc == 4 ? TickResult.PROGRAM_FINISHED : TickResult.INSTRUCTION_COMPLETED, result);
                    assertEquals(pc + 1, pcb.programCounter());
                    assertEquals(pc + 1, cpu.programCounter());
                    assertTrue(progress.isIdle());
                    assertEquals(0, progress.consumedTicks());
                    assertTrue(progress.instruction().isEmpty());
                }
                for (int offset = 0; offset < program.size(); offset++) {
                    assertSame(program.get(offset), memory.readInstruction(pcb.memoryBounds(), offset));
                }
            }
        }
        assertEquals(11, calls);
        assertEquals(5, cpu.accumulator());
        assertEquals(10, cpu.readRegister(RegisterName.BX));
        assertEquals(ProcessState.TERMINATED, pcb.state());
    }

    @Test
    void finalTickErrorClearsProgressWithoutCommittingAndPreservesCause() {
        var instruction = new AddInstruction(RegisterName.AX);
        var pcbLoaded = new ProgramLoader().loadWithAllocation(memory, 1, List.of(instruction));
        var pcb = pcbLoaded.pcb();
        cpu.writeAccumulator(32767);
        cpu.writeRegister(RegisterName.AX, 1);
        assertEquals(TickResult.IN_PROGRESS, engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, progress, pcbLoaded.userAllocation()));
        assertEquals(TickResult.IN_PROGRESS, engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, progress, pcbLoaded.userAllocation()));
        var failure = assertThrows(ExecutionEngineException.class, () -> engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, progress, pcbLoaded.userAllocation()));
        assertInstanceOf(InvalidRegisterValueException.class, failure.getCause());
        assertEquals(32767, cpu.accumulator());
        assertEquals(0, cpu.programCounter());
        assertEquals(0, pcb.programCounter());
        assertSame(instruction, cpu.instructionRegister().orElseThrow());
        assertTrue(progress.isIdle());
        assertEquals(0, progress.consumedTicks());
    }

    @Test
    void rejectsContextMismatchWithoutConsumingOriginalProgress() {
        var pcbLoaded = new ProgramLoader().loadWithAllocation(memory, 1, List.of(new AddInstruction(RegisterName.AX)));
        var pcb = pcbLoaded.pcb();
        engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, progress, pcbLoaded.userAllocation());
        var impostor = new ProcessControlBlock(1, 32, 1);
        impostor.changeState(ProcessState.RUNNING);
        assertThrows(ExecutionEngineException.class, () -> engine.executeTick(filesystem, screen, keyboard, memory, cpu, impostor, progress, pcbLoaded.userAllocation()));
        assertThrows(ExecutionEngineException.class, () -> engine.executeTick(filesystem, screen, keyboard, memory, new CpuRegisters<>(), pcb, progress, pcbLoaded.userAllocation()));
        assertThrows(ExecutionEngineException.class, () -> engine.executeTick(filesystem, screen, keyboard, new MainMemory(new MemoryConfiguration(128, 32)), cpu, pcb, progress, pcbLoaded.userAllocation()));
        pcb.setProgramCounter(1);
        assertThrows(ExecutionEngineException.class, () -> engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, progress, pcbLoaded.userAllocation()));
        assertEquals(1, progress.consumedTicks());
        pcb.setProgramCounter(0);
        assertEquals(TickResult.IN_PROGRESS, engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, progress, pcbLoaded.userAllocation()));
        assertEquals(TickResult.PROGRAM_FINISHED, engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, progress, pcbLoaded.userAllocation()));
    }

    @Test
    void statelessEngineInterleavesIndependentProgressAndTerminalGuardDoesNotFetch() {
        var firstLoaded = new ProgramLoader().loadWithAllocation(memory, 1, List.of(new LoadInstruction(RegisterName.AX)));
        var first = firstLoaded.pcb();
        var secondLoaded = new ProgramLoader().loadWithAllocation(memory, 2, List.of(new AddInstruction(RegisterName.BX)));
        var second = secondLoaded.pcb();
        var otherCpu = new CpuRegisters<Instruction>();
        var otherProgress = new ExecutionProgress();
        assertEquals(TickResult.IN_PROGRESS, engine.executeTick(filesystem, screen, keyboard, memory, cpu, first, progress, firstLoaded.userAllocation()));
        assertEquals(TickResult.IN_PROGRESS, engine.executeTick(filesystem, screen, keyboard, memory, otherCpu, second, otherProgress, secondLoaded.userAllocation()));
        assertEquals(TickResult.PROGRAM_FINISHED, engine.executeTick(filesystem, screen, keyboard, memory, cpu, first, progress, firstLoaded.userAllocation()));
        assertEquals(TickResult.IN_PROGRESS, engine.executeTick(filesystem, screen, keyboard, memory, otherCpu, second, otherProgress, secondLoaded.userAllocation()));
        assertEquals(TickResult.PROGRAM_FINISHED, engine.executeTick(filesystem, screen, keyboard, memory, otherCpu, second, otherProgress, secondLoaded.userAllocation()));
        var terminal = new ProcessControlBlock(3, 0, 1);
        terminal.changeState(ProcessState.READY);
        terminal.setProgramCounter(1);
        var ir = cpu.instructionRegister();
        assertEquals(TickResult.PROGRAM_FINISHED, engine.executeTick(filesystem, screen, keyboard, memory, cpu, terminal, progress, null));
        assertEquals(ir, cpu.instructionRegister());
        assertTrue(progress.isIdle());
        assertThrows(NullPointerException.class, () -> engine.executeTick(filesystem, screen, keyboard, memory, cpu, terminal, null, null));
    }
}
