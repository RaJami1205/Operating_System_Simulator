package io.github.rajami1205.osimulator.model.execution;

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
    private final ExecutionEngine engine = new ExecutionEngine();
    private final MainMemory memory = new MainMemory(new MemoryConfiguration(128, 32));
    private final CpuRegisters<Instruction> cpu = new CpuRegisters<>();
    private final ExecutionProgress progress = new ExecutionProgress();

    @Test
    void fiveInstructionsConsumeExactlyElevenTicksWithAtomicEffectsAndPersistentIr() {
        var program = List.<Instruction>of(new MovInstruction(RegisterName.AX, 5),
                new LoadInstruction(RegisterName.AX), new AddInstruction(RegisterName.AX),
                new StoreInstruction(RegisterName.BX), new SubInstruction(RegisterName.AX));
        var pcb = new ProgramLoader().load(memory, 1, program);
        assertTrue(progress.isIdle());
        assertTrue(progress.instruction().isEmpty());
        assertEquals(0, progress.consumedTicks());
        int calls = 0;
        for (int pc = 0; pc < program.size(); pc++) {
            var instruction = program.get(pc);
            var before = cpu.snapshot();
            int weight = instruction.executionWeight().ticks();
            for (int tick = 1; tick <= weight; tick++) {
                var result = engine.executeTick(memory, cpu, pcb, progress);
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
        var pcb = new ProgramLoader().load(memory, 1, List.of(instruction));
        cpu.writeAccumulator(32767);
        cpu.writeRegister(RegisterName.AX, 1);
        assertEquals(TickResult.IN_PROGRESS, engine.executeTick(memory, cpu, pcb, progress));
        assertEquals(TickResult.IN_PROGRESS, engine.executeTick(memory, cpu, pcb, progress));
        var failure = assertThrows(ExecutionEngineException.class, () -> engine.executeTick(memory, cpu, pcb, progress));
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
        var pcb = new ProgramLoader().load(memory, 1, List.of(new AddInstruction(RegisterName.AX)));
        engine.executeTick(memory, cpu, pcb, progress);
        var impostor = new ProcessControlBlock(1, 32, 1);
        impostor.changeState(ProcessState.RUNNING);
        assertThrows(ExecutionEngineException.class, () -> engine.executeTick(memory, cpu, impostor, progress));
        assertThrows(ExecutionEngineException.class, () -> engine.executeTick(memory, new CpuRegisters<>(), pcb, progress));
        assertThrows(ExecutionEngineException.class, () -> engine.executeTick(new MainMemory(new MemoryConfiguration(128, 32)), cpu, pcb, progress));
        pcb.setProgramCounter(1);
        assertThrows(ExecutionEngineException.class, () -> engine.executeTick(memory, cpu, pcb, progress));
        assertEquals(1, progress.consumedTicks());
        pcb.setProgramCounter(0);
        assertEquals(TickResult.IN_PROGRESS, engine.executeTick(memory, cpu, pcb, progress));
        assertEquals(TickResult.PROGRAM_FINISHED, engine.executeTick(memory, cpu, pcb, progress));
    }

    @Test
    void statelessEngineInterleavesIndependentProgressAndTerminalGuardDoesNotFetch() {
        var first = new ProgramLoader().load(memory, 1, List.of(new LoadInstruction(RegisterName.AX)));
        var second = new ProgramLoader().load(memory, 2, List.of(new AddInstruction(RegisterName.BX)));
        var otherCpu = new CpuRegisters<Instruction>();
        var otherProgress = new ExecutionProgress();
        assertEquals(TickResult.IN_PROGRESS, engine.executeTick(memory, cpu, first, progress));
        assertEquals(TickResult.IN_PROGRESS, engine.executeTick(memory, otherCpu, second, otherProgress));
        assertEquals(TickResult.PROGRAM_FINISHED, engine.executeTick(memory, cpu, first, progress));
        assertEquals(TickResult.IN_PROGRESS, engine.executeTick(memory, otherCpu, second, otherProgress));
        assertEquals(TickResult.PROGRAM_FINISHED, engine.executeTick(memory, otherCpu, second, otherProgress));
        var terminal = new ProcessControlBlock(3, 0, 1);
        terminal.changeState(ProcessState.READY);
        terminal.setProgramCounter(1);
        var ir = cpu.instructionRegister();
        assertEquals(TickResult.PROGRAM_FINISHED, engine.executeTick(memory, cpu, terminal, progress));
        assertEquals(ir, cpu.instructionRegister());
        assertTrue(progress.isIdle());
        assertThrows(NullPointerException.class, () -> engine.executeTick(memory, cpu, terminal, null));
    }
}
