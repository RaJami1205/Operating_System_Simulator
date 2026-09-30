package io.github.rajami1205.osimulator.model.execution;

import io.github.rajami1205.osimulator.model.filesystem.SimulatedFileSystem;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.instruction.operand.InterruptVector;
import io.github.rajami1205.osimulator.model.io.*;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BasicInterruptExecutionTest {
    private final ExecutionEngine engine = new ExecutionEngine();
    private final MainMemory memory = new MainMemory(new MemoryConfiguration(128,32));
    private final CpuRegisters<Instruction> cpu = new CpuRegisters<>();
    private final ExecutionProgress progress = new ExecutionProgress();
    private final KeyboardDevice keyboard = new KeyboardDevice();
    private final ScreenDevice screen = new ScreenDevice();
    private final SimulatedFileSystem filesystem = new SimulatedFileSystem(new SecondaryStorage(128, 64));
    private ProcessControlBlock pcb;
    private InterruptInstruction interrupt(InterruptVector vector) { return new InterruptInstruction(vector); }
    private void load(Instruction... instructions) { pcb = new ProgramLoader().load(memory,1,List.of(instructions)); }
    private TickResult tick() { return engine.executeTick(filesystem, screen,keyboard,memory,cpu,pcb,progress); }
    private TickResult complete() { return engine.completeKeyboardInput(keyboard,memory,cpu,pcb,progress); }
    private void pc(int expected) { assertEquals(expected,cpu.programCounter()); assertEquals(expected,pcb.programCounter()); }

    @Test void screenAppendsOncePerFinalTickInOrderAndCanFinish() {
        load(interrupt(InterruptVector.SCREEN),interrupt(InterruptVector.SCREEN));
        cpu.writeRegister(RegisterName.DX,-32768);
        assertEquals(TickResult.IN_PROGRESS,tick()); assertTrue(screen.outputs().isEmpty()); pc(0);
        assertEquals(TickResult.INSTRUCTION_COMPLETED,tick()); pc(1);
        assertEquals(List.of(-32768),screen.outputs());
        cpu.writeRegister(RegisterName.DX,32767);
        assertEquals(TickResult.IN_PROGRESS,tick()); assertEquals(List.of(-32768),screen.outputs());
        assertEquals(TickResult.PROGRAM_FINISHED,tick()); pc(2);
        assertEquals(List.of(-32768,32767),screen.outputs());
    }
    @Test void terminatePreservesItsPcAndSkipsFollowingInstructions() {
        load(new IncInstruction(),interrupt(InterruptVector.TERMINATE),new IncInstruction());
        tick(); pc(1);
        assertEquals(TickResult.IN_PROGRESS,tick()); assertEquals(ProcessState.RUNNING,pcb.state());
        assertEquals(TickResult.PROGRAM_FINISHED,tick()); pc(1);
        assertEquals(ProcessState.TERMINATED,pcb.state()); assertEquals(1,cpu.accumulator());
        assertTrue(progress.isIdle());
        assertThrows(ExecutionEngineException.class,this::tick);
        assertEquals(1,cpu.accumulator());
    }
    @Test void prequeuedInputCompletesOnFinalTicksInFifoOrder() {
        load(interrupt(InterruptVector.KEYBOARD),interrupt(InterruptVector.KEYBOARD));
        keyboard.submit(0); keyboard.submit(255); cpu.writeRegister(RegisterName.DX,42);
        assertEquals(TickResult.IN_PROGRESS,tick()); assertEquals(42,cpu.readRegister(RegisterName.DX));
        assertEquals(TickResult.INSTRUCTION_COMPLETED,tick()); pc(1);
        assertEquals(0,cpu.readRegister(RegisterName.DX)); assertEquals(ProcessState.RUNNING,pcb.state());
        assertFalse(progress.waitingForInput()); assertTrue(progress.isIdle());
        tick(); assertEquals(0,cpu.readRegister(RegisterName.DX));
        assertEquals(TickResult.PROGRAM_FINISHED,tick()); pc(2);
        assertEquals(255,cpu.readRegister(RegisterName.DX)); assertFalse(keyboard.hasInput());
    }
    @Test void pendingWaitPreservesCostAndContextAndCompletesWithoutTick() {
        var input=interrupt(InterruptVector.KEYBOARD);
        load(input,new IncInstruction()); cpu.writeRegister(RegisterName.DX,77);
        assertEquals(TickResult.IN_PROGRESS,tick());
        assertEquals(TickResult.WAITING_FOR_INPUT,tick());
        assertEquals(ProcessState.BLOCKED,pcb.state()); assertTrue(progress.waitingForInput());
        assertSame(input,cpu.instructionRegister().orElseThrow());
        for(int i=0;i<5;i++) {
            assertEquals(TickResult.WAITING_FOR_INPUT,tick()); pc(0);
            assertEquals(2,progress.consumedTicks()); assertEquals(77,cpu.readRegister(RegisterName.DX));
        }
        assertEquals(TickResult.WAITING_FOR_INPUT,complete());
        keyboard.submit(255);
        // Normal tick does not silently complete external I/O, even with queued input.
        assertEquals(TickResult.WAITING_FOR_INPUT,tick()); assertTrue(keyboard.hasInput());
        assertEquals(TickResult.INSTRUCTION_COMPLETED,complete()); pc(1);
        assertEquals(255,cpu.readRegister(RegisterName.DX)); assertEquals(ProcessState.READY,pcb.state());
        assertTrue(progress.isIdle()); assertFalse(progress.waitingForInput()); assertEquals(0,progress.consumedTicks());
        assertThrows(ExecutionEngineException.class,this::complete);
        assertEquals(TickResult.PROGRAM_FINISHED,tick());
    }
    @Test void lastInputCompletionTerminatesAndMismatchDoesNotConsumeInput() {
        load(interrupt(InterruptVector.KEYBOARD)); tick(); tick(); keyboard.submit(0);
        var other=new ProcessControlBlock(1,pcb.programStartAddress(),1); other.changeState(ProcessState.BLOCKED);
        assertThrows(ExecutionEngineException.class,()->engine.completeKeyboardInput(keyboard,memory,cpu,other,progress));
        assertThrows(ExecutionEngineException.class,()->engine.executeTick(filesystem, screen,keyboard,memory,new CpuRegisters<>(),pcb,progress));
        assertTrue(keyboard.hasInput()); assertEquals(2,progress.consumedTicks());
        assertEquals(TickResult.PROGRAM_FINISHED,complete()); pc(1);
        assertEquals(ProcessState.TERMINATED,pcb.state()); assertTrue(progress.isIdle());
    }
}
