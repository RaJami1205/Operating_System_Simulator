package io.github.rajami1205.osimulator.model.execution;

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
        final MainMemory memory = new MainMemory(new MemoryConfiguration(128, 32));
        final CpuRegisters<Instruction> cpu = new CpuRegisters<>();
        final ExecutionProgress progress = new ExecutionProgress();
        final ExecutionEngine engine = new ExecutionEngine();
        final ProcessControlBlock pcb;
        Session(Instruction... instructions) { pcb = new ProgramLoader().load(memory, 1, List.of(instructions)); }
        TickResult tick() { return engine.executeTick(memory, cpu, pcb, progress); }
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
        for (int[] sample : new int[][]{{0,1,2},{2,-3,0},{0,0,1},{1,-1,1}}) {
            var jump = new JmpInstruction(new BranchDisplacement(sample[1]));
            Instruction[] program = {jump, jump, jump};
            var s = new Session(program);
            s.pc(sample[0]);
            for (int repeat = 0; repeat < (sample[1] == -1 ? 3 : 1); repeat++) {
                assertEquals(TickResult.IN_PROGRESS, s.tick());
                s.assertPc(sample[0]);
                assertEquals(TickResult.INSTRUCTION_COMPLETED, s.tick());
                s.assertPc(sample[2]);
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
                    if (taken && displacement != 1) s.failure(jump, null);
                    else {
                        assertEquals(TickResult.INSTRUCTION_COMPLETED, s.tick());
                        s.assertPc(taken ? 2 : 1);
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
        for (int displacement : new int[]{2, -2, 3, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            var jump = new JmpInstruction(new BranchDisplacement(displacement));
            var s = new Session(jump, new IncInstruction(), new IncInstruction());
            s.tick();
            s.failure(jump, null);
        }
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
