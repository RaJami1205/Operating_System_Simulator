package io.github.rajami1205.osimulator.model.execution;

import io.github.rajami1205.osimulator.model.io.KeyboardDevice;

import io.github.rajami1205.osimulator.model.io.ScreenDevice;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.cpu.exception.InvalidRegisterValueException;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.memory.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TransferArithmeticExecutionTest {
    private final ScreenDevice screen = new ScreenDevice();
    private final KeyboardDevice keyboard = new KeyboardDevice();
    private final ExecutionEngine engine = new ExecutionEngine();

    private CpuRegisters<Instruction> cpu() {
        var cpu = new CpuRegisters<Instruction>();
        cpu.writeAccumulator(12);
        for (var register : RegisterName.values()) cpu.writeRegister(register, register.ordinal() * 11 - 20);
        cpu.writeConditionFlags(new ConditionFlags(true, true));
        return cpu;
    }

    private void execute(Instruction instruction, CpuRegisters<Instruction> cpu) {
        var memory = new MainMemory(new MemoryConfiguration(128, 32));
        var pcb = new ProgramLoader().load(memory, 1, List.of(instruction));
        var progress = new ExecutionProgress();
        var flags = cpu.conditionFlags();
        assertEquals(TickResult.PROGRAM_FINISHED, engine.executeTick(screen, keyboard, memory, cpu, pcb, progress));
        assertEquals(1, cpu.programCounter());
        assertEquals(1, pcb.programCounter());
        assertSame(instruction, cpu.instructionRegister().orElseThrow());
        assertEquals(flags, cpu.conditionFlags());
        assertTrue(progress.isIdle());
    }

    @Test
    void movAndSwapSupportAllPairsIncludingSameRegisterAndExtremeValues() {
        for (var left : RegisterName.values()) for (var right : RegisterName.values()) {
            var cpu = cpu();
            cpu.writeRegister(left, -32768);
            cpu.writeRegister(right, 32767);
            var before = cpu.snapshot();
            int source = cpu.readRegister(right);
            execute(new MovInstruction(left, right), cpu);
            assertEquals(source, cpu.readRegister(left));
            assertEquals(source, cpu.readRegister(right));
            assertEquals(before.accumulator(), cpu.accumulator());
            for (var other : RegisterName.values()) if (other != left) {
                int expected = switch (other) { case AX -> before.ax(); case BX -> before.bx(); case CX -> before.cx(); case DX -> before.dx(); };
                assertEquals(expected, cpu.readRegister(other));
            }
            cpu = cpu();
            cpu.writeRegister(left, -32768);
            cpu.writeRegister(right, 32767);
            int a = cpu.readRegister(left), b = cpu.readRegister(right);
            execute(new SwapInstruction(left, right), cpu);
            assertEquals(b, cpu.readRegister(left));
            assertEquals(a, cpu.readRegister(right));
            assertEquals(12, cpu.accumulator());
        }
    }

    @Test
    void incDecModifyOnlyChosenTargetInOneTick() {
        for (boolean increment : new boolean[]{true, false}) {
            int delta = increment ? 1 : -1;
            var accumulatorCpu = cpu();
            execute(increment ? new IncInstruction() : new DecInstruction(), accumulatorCpu);
            assertEquals(12 + delta, accumulatorCpu.accumulator());
            for (var register : RegisterName.values()) {
                assertEquals(register.ordinal() * 11 - 20, accumulatorCpu.readRegister(register));
                var cpu = cpu();
                int before = cpu.readRegister(register);
                execute(increment ? new IncInstruction(register) : new DecInstruction(register), cpu);
                assertEquals(before + delta, cpu.readRegister(register));
                assertEquals(12, cpu.accumulator());
                for (var other : RegisterName.values()) if (other != register)
                    assertEquals(other.ordinal() * 11 - 20, cpu.readRegister(other));
            }
        }
    }

    @Test
    void boundariesFailAtomicallyForAccumulatorAndEveryRegister() {
        for (boolean increment : new boolean[]{true, false}) for (int target = -1; target < RegisterName.values().length; target++) {
            int boundary = increment ? 32767 : -32768;
            var cpu = cpu();
            Instruction instruction;
            if (target == -1) {
                cpu.writeAccumulator(boundary);
                instruction = increment ? new IncInstruction() : new DecInstruction();
            } else {
                var register = RegisterName.values()[target];
                cpu.writeRegister(register, boundary);
                instruction = increment ? new IncInstruction(register) : new DecInstruction(register);
            }
            var memory = new MainMemory(new MemoryConfiguration(128, 32));
            var pcb = new ProgramLoader().load(memory, 1, List.of(instruction));
            var progress = new ExecutionProgress();
            cpu.loadInstructionRegister(instruction);
            var before = cpu.snapshot();
            var failure = assertThrows(ExecutionEngineException.class, () -> engine.executeTick(screen, keyboard, memory, cpu, pcb, progress));
            assertInstanceOf(InvalidRegisterValueException.class, failure.getCause());
            assertEquals(before, cpu.snapshot());
            assertEquals(0, pcb.programCounter());
            assertTrue(progress.isIdle());
        }
    }
}
