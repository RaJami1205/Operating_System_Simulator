package io.github.rajami1205.osimulator.model.execution;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.filesystem.SimulatedFileSystem;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.io.*;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionAllocationTest {
    @Test void invalidHandlesAndReplacementMidInstructionNeverConsumeProgress() {
        var memory = new MainMemory(new MemoryConfiguration(128,32));
        var loaded = new ProgramLoader().loadWithAllocation(memory,1,List.of(new AddInstruction(RegisterName.AX)));
        var cpu = new CpuRegisters<Instruction>(); var progress = new ExecutionProgress(); var engine = new ExecutionEngine();
        var filesystem = new SimulatedFileSystem(new SecondaryStorage(128,64)); var screen = new ScreenDevice(); var keyboard = new KeyboardDevice();
        var before = cpu.snapshot();
        for(var bad : List.of(memory.allocateKernel(1), new MainMemory(new MemoryConfiguration(128,32)).allocateUser(1), memory.allocateUser(2))) {
            assertThrows(ExecutionEngineException.class,()->engine.executeTick(filesystem,screen,keyboard,memory,cpu,loaded.pcb(),progress,bad));
            assertEquals(before,cpu.snapshot()); assertTrue(progress.isIdle()); assertEquals(0,progress.consumedTicks());
        }
        assertEquals(TickResult.IN_PROGRESS,engine.executeTick(filesystem,screen,keyboard,memory,cpu,loaded.pcb(),progress,loaded.userAllocation()));
        var active = cpu.snapshot(); memory.release(loaded.userAllocation());
        var replacement = memory.allocateUser(1); memory.writeUserBlock(replacement,List.of(new IncInstruction()));
        assertEquals(loaded.userAllocation().base(),replacement.base());
        for(var bad : List.of(loaded.userAllocation(),replacement)) {
            assertThrows(ExecutionEngineException.class,()->engine.executeTick(filesystem,screen,keyboard,memory,cpu,loaded.pcb(),progress,bad));
            assertEquals(1,progress.consumedTicks()); assertEquals(active,cpu.snapshot());
        }
    }
}
