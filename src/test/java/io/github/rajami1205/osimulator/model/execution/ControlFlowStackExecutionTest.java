package io.github.rajami1205.osimulator.model.execution;

import io.github.rajami1205.osimulator.model.filesystem.SimulatedFileSystem;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import io.github.rajami1205.osimulator.model.io.KeyboardDevice;

import io.github.rajami1205.osimulator.model.io.ScreenDevice;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.instruction.operand.*;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.process.exception.*;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ControlFlowStackExecutionTest {
    private static final class Session {
        private final ScreenDevice screen = new ScreenDevice();
    private final SimulatedFileSystem filesystem = new SimulatedFileSystem(new SecondaryStorage(128, 64));
        private final KeyboardDevice keyboard = new KeyboardDevice();
        final MainMemory memory = new MainMemory(new MemoryConfiguration(128, 32));
        final CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        final ExecutionProgress progress = new ExecutionProgress();
        final ExecutionEngine engine = new ExecutionEngine();
        final ProcessControlBlock pcb;
        final io.github.rajami1205.osimulator.model.memory.MemoryAllocation allocation;
        Session(Instruction... instructions) { var loaded = new ProgramLoader().loadWithAllocation(memory, 1, List.of(instructions)); pcb = loaded.pcb(); allocation = loaded.userAllocation(); }
        TickResult tick() { return engine.executeTick(filesystem, screen, keyboard, memory, cpu, pcb, progress, allocation); }
        void pc(int value) { pcb.setProgramCounter(value); cpu.setProgramCounter(value); }
        void assertPc(int value) { assertEquals(value, cpu.programCounter()); assertEquals(value, pcb.programCounter()); }
        void failure(Instruction instruction, Class<? extends Throwable> cause) {
            int pc = pcb.programCounter();
            var flags = cpu.conditionFlags();
            var stack = pcb.stack().values();
            var failure = assertThrows(ExecutionEngineException.class, this::tick);
            if (cause != null) assertInstanceOf(cause, failure.getCause());
            assertPc(pc);
            assertEquals(flags, cpu.conditionFlags());
            assertEquals(stack, pcb.stack().values());
            assertSame(instruction, cpu.instructionRegister().orElseThrow());
            assertTrue(progress.isIdle());
        }
    }

    @Test void comparisonUsesActiveCpuAndOnlyChangesEqualityOnFinalTick() {
        for (boolean equal : new boolean[]{false, true}) {
            var cmp = new CmpInstruction(RegisterName.AX, RegisterName.BX);
            var s = new Session(cmp);
            s.cpu.writeRegister(RegisterName.AX, -32768);
            s.cpu.writeRegister(RegisterName.BX, equal ? -32768 : 32767);
            var flags = new ConditionFlags(!equal, true);
            s.cpu.writeConditionFlags(flags);
            var saved = s.pcb.cpuContext();
            assertEquals(TickResult.IN_PROGRESS, s.tick());
            assertEquals(flags, s.cpu.conditionFlags());
            s.assertPc(0);
            assertEquals(TickResult.PROGRAM_FINISHED, s.tick());
            assertEquals(new ConditionFlags(equal, true), s.cpu.conditionFlags());
            assertEquals(-32768, s.cpu.readRegister(RegisterName.AX));
            assertEquals(equal ? -32768 : 32767, s.cpu.readRegister(RegisterName.BX));
            assertEquals(saved.conditionFlags(), s.pcb.cpuContext().conditionFlags());
        }
    }

    @Test void jumpsCoverForwardBackwardZeroBoundariesAndSelfLoop() {
        for (int[] sample : new int[][]{{0,1,1},{0,3,3},{1,-1,0},{4,-4,0},{0,0,0},{4,0,4},{0,4,4},{48,-4,44},{20,3,23}}) {
            var jump = new JmpInstruction(new BranchDisplacement(sample[1]));
            Instruction[] program = Collections.nCopies(Math.max(5, sample[0] + 4), jump).toArray(Instruction[]::new);
            var s = new Session(program);
            s.pc(sample[0]);
            for (int repeat = 0; repeat < (sample[1] == 0 ? 3 : 1); repeat++) {
                assertEquals(TickResult.IN_PROGRESS, s.tick());
                s.assertPc(sample[0]);
                assertSame(jump, s.cpu.instructionRegister().orElseThrow());
                assertEquals(TickResult.INSTRUCTION_COMPLETED, s.tick());
                s.assertPc(sample[2]);
                assertSame(jump, s.cpu.instructionRegister().orElseThrow());
                assertTrue(s.progress.isIdle());
            }
        }
    }

    @Test void conditionsReadActiveFlagsAndValidateOnlyTakenTargets() {
        for (boolean je : new boolean[]{true, false}) {
            for (boolean taken : new boolean[]{true, false}) {
                for (int displacement : new int[]{1, 2, -2, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
                    Instruction jump = je ? new JeInstruction(new BranchDisplacement(displacement))
                            : new JneInstruction(new BranchDisplacement(displacement));
                    var s = new Session(jump, new IncInstruction(), new IncInstruction());
                    var flags = new ConditionFlags(je == taken, true);
                    s.cpu.writeConditionFlags(flags);
                    assertEquals(TickResult.IN_PROGRESS, s.tick());
                    s.assertPc(0);
                    if (taken && displacement != 1 && displacement != 2) s.failure(jump, null);
                    else {
                        assertEquals(TickResult.INSTRUCTION_COMPLETED, s.tick());
                        s.assertPc(taken ? displacement : 1);
                    }
                    assertEquals(flags, s.cpu.conditionFlags());
                }
            }
        }
        var last = new Session(new JeInstruction(new BranchDisplacement(Integer.MAX_VALUE)));
        last.tick();
        assertEquals(TickResult.PROGRAM_FINISHED, last.tick());
        last.assertPc(1);
    }

    @Test void invalidUnconditionalTargetsFailWithoutWrappingOrPublishingPc() {
        for (int displacement : new int[]{-1, -2, 3, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            var jump = new JmpInstruction(new BranchDisplacement(displacement));
            var s = new Session(jump, new IncInstruction(), new IncInstruction());
            s.tick();
            s.failure(jump, null);
        }
    }

    @Test void conditionalBackwardBranchesPublishOnlyOnSecondTick() {
        for (boolean je : new boolean[]{true, false}) {
            Instruction jump = je ? new JeInstruction(new BranchDisplacement(-2))
                    : new JneInstruction(new BranchDisplacement(-2));
            var s = new Session(new IncInstruction(), new IncInstruction(), jump);
            s.pc(2);
            s.cpu.writeConditionFlags(new ConditionFlags(je, true));
            assertEquals(TickResult.IN_PROGRESS, s.tick());
            s.assertPc(2); assertSame(jump, s.cpu.instructionRegister().orElseThrow());
            assertEquals(TickResult.INSTRUCTION_COMPLETED, s.tick());
            s.assertPc(0); assertSame(jump, s.cpu.instructionRegister().orElseThrow());
            assertEquals(new ConditionFlags(je, true), s.cpu.conditionFlags());
        }
    }

    @Test void reviewedLoopFetchesIncAtTwoAndPositiveBranchSkipsUnwantedOutput() {
        var instructions = new io.github.rajami1205.osimulator.infrastructure.asm.AsmParser().parse(List.of(
                "MOV CX, 0", "MOV BX, 3", "INC CX", "MOV DX, CX", "INT 10H", "CMP CX, BX", "JNE -4",
                "JMP +3", "MOV DX, 99", "INT 10H", "MOV DX, 7", "INT 10H", "INT 20H"));
        var s = new Session(instructions.toArray(Instruction[]::new));
        int returns = 0;
        for (int tick = 0; tick < 100 && s.pcb.state() != ProcessState.TERMINATED; tick++) {
            int previousPc = s.pcb.programCounter();
            var result = s.tick();
            if (previousPc == 6 && result == TickResult.INSTRUCTION_COMPLETED && !s.cpu.conditionFlags().equal()) {
                s.assertPc(2);
                s.tick();
                assertSame(instructions.get(2), s.cpu.instructionRegister().orElseThrow());
                assertInstanceOf(IncInstruction.class, s.cpu.instructionRegister().orElseThrow());
                returns++;
            }
        }
        assertEquals(2, returns);
        assertEquals(ProcessState.TERMINATED, s.pcb.state());
        assertEquals(List.of(1, 2, 3, 7), s.screen.outputs());
    }

    @Test void reviewedJneAt48ReturnsTo44() {
        var jump = new JneInstruction(new BranchDisplacement(-4));
        var s = new Session(Collections.nCopies(49, jump).toArray(Instruction[]::new));
        s.pc(48);
        assertEquals(TickResult.IN_PROGRESS, s.tick()); s.assertPc(48);
        assertSame(jump, s.cpu.instructionRegister().orElseThrow());
        assertEquals(TickResult.INSTRUCTION_COMPLETED, s.tick()); s.assertPc(44);
    }

    @Test void pushPopEveryRegisterAndStackIsolation() {
        for (var register : RegisterName.values()) {
            var s = new Session(new PushInstruction(register), new PopInstruction(register));
            s.cpu.writeRegister(register, -32768);
            assertEquals(TickResult.INSTRUCTION_COMPLETED, s.tick());
            assertEquals(List.of(-32768), s.pcb.stack().values());
            assertTrue(new ProcessControlBlock(2, 40, 2).stack().isEmpty());
            s.cpu.writeRegister(register, 32767);
            assertEquals(TickResult.PROGRAM_FINISHED, s.tick());
            assertEquals(-32768, s.cpu.readRegister(register));
            assertTrue(s.pcb.stack().isEmpty());
        }
    }

    @Test void stackFailuresPreserveStateIrAndCauses() {
        var push = new PushInstruction(RegisterName.AX);
        var full = new Session(push);
        full.pcb.stack().pushAll(List.of(1,2,3,4,5));
        full.failure(push, ProcessStackOverflowException.class);
        var pop = new PopInstruction(RegisterName.BX);
        var empty = new Session(pop);
        empty.cpu.writeRegister(RegisterName.BX, 42);
        empty.failure(pop, ProcessStackUnderflowException.class);
        assertEquals(42, empty.cpu.readRegister(RegisterName.BX));
    }

    @Test void parametersCommitOnThirdTickAndPopInTextualOrder() {
        var values = List.of(new ImmediateOperand(-32768), new ImmediateOperand(32767), new ImmediateOperand(3));
        for (int count = 1; count <= 3; count++) {
            var param = new ParamInstruction(values.subList(0, count));
            var program = new ArrayList<Instruction>();
            program.add(param);
            for (int i = 0; i < count; i++) program.add(new PopInstruction(RegisterName.values()[i]));
            var s = new Session(program.toArray(Instruction[]::new));
            for (int tick = 0; tick < 2; tick++) {
                assertEquals(TickResult.IN_PROGRESS, s.tick());
                assertTrue(s.pcb.stack().isEmpty());
                s.assertPc(0);
            }
            assertEquals(TickResult.INSTRUCTION_COMPLETED, s.tick());
            assertEquals(values.subList(0,count).reversed().stream().map(ImmediateOperand::value).toList(), s.pcb.stack().values());
            for (int i = 0; i < count; i++) {
                s.tick();
                assertEquals(values.get(i).value(), s.cpu.readRegister(RegisterName.values()[i]));
            }
            assertTrue(s.pcb.stack().isEmpty());
        }
        var param = new ParamInstruction(values);
        var full = new Session(param);
        full.pcb.stack().pushAll(List.of(7,8,9));
        full.tick(); full.tick();
        full.failure(param, ProcessStackOverflowException.class);
    }
}
