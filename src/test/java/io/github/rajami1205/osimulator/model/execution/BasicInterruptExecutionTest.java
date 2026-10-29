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
    private final ScreenDevice screen = new ScreenDevice();
    private final SimulatedFileSystem filesystem = new SimulatedFileSystem(new SecondaryStorage(128, 64));
    private ProcessControlBlock pcb;
    private io.github.rajami1205.osimulator.model.memory.MemoryAllocation allocation;
    private InterruptInstruction interrupt(InterruptVector vector) { return new InterruptInstruction(vector); }
    private void load(Instruction... instructions) { var loaded = new ProgramLoader().loadWithAllocation(memory,1,List.of(instructions)); pcb = loaded.pcb(); allocation = loaded.userAllocation(); }
    private TickResult tick() { return engine.executeTick(filesystem, screen,memory,cpu,pcb,allocation); }
    private void pc(int expected) { assertEquals(4096 + expected,cpu.programCounter()); assertEquals(4096 + expected,pcb.programCounter()); }

    @Test void screenAppendsOncePerTickInOrderAndCanFinish() {
        load(interrupt(InterruptVector.SCREEN),interrupt(InterruptVector.SCREEN));
        cpu.writeRegister(RegisterName.DX,-32768);
        assertEquals(TickResult.INSTRUCTION_COMPLETED,tick()); pc(1);
        assertEquals(List.of(-32768),screen.outputs());
        cpu.writeRegister(RegisterName.DX,32767);
        assertEquals(TickResult.PROGRAM_FINISHED,tick()); pc(2);
        assertEquals(List.of(-32768,32767),screen.outputs());
    }
    @Test void terminatePreservesItsPcAndSkipsFollowingInstructions() {
        load(new IncInstruction(),interrupt(InterruptVector.TERMINATE),new IncInstruction());
        tick(); pc(1);
        assertEquals(TickResult.PROGRAM_FINISHED,tick()); pc(1);
        assertEquals(ProcessState.TERMINATED,pcb.state()); assertEquals(1,cpu.accumulator());

        assertThrows(ExecutionEngineException.class,this::tick);
        assertEquals(1,cpu.accumulator());
    }
}
