package io.github.rajami1205.osimulator.application.program;

import io.github.rajami1205.osimulator.application.program.exception.ProgramLoadException;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.support.MemoryFaults;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProgramLoadResultTest {
    @Test
    void transfersOriginalActiveHandleAndReadyLogicalContext() {
        var memory = new MainMemory(new MemoryConfiguration(128, 32));
        var instruction = new LoadInstruction(RegisterName.AX);
        var loaded = new ProgramLoader().loadWithAllocation(memory, 7, List.of(instruction));
        assertEquals(ProcessState.READY, loaded.pcb().state());
        assertEquals(0, loaded.pcb().programCounter());
        assertEquals(new ProcessMemoryBounds(loaded.userAllocation().base(), loaded.userAllocation().size()), loaded.pcb().memoryBounds());
        assertSame(instruction, memory.readInstruction(loaded.pcb().memoryBounds(), 0));
        memory.release(loaded.userAllocation());
        assertTrue(memory.isEmpty(32));
        assertEquals(32, memory.allocateUser(96).base());
    }

    @Test
    void resultRejectsNullWrongRegionAndMismatchingBounds() {
        var memory = new MainMemory(new MemoryConfiguration(128, 32));
        var user = memory.allocateUser(2);
        var pcb = new ProcessControlBlock(1, 32, 2);
        assertThrows(NullPointerException.class, () -> new ProgramLoadResult(null, user));
        assertThrows(NullPointerException.class, () -> new ProgramLoadResult(pcb, null));
        assertThrows(IllegalArgumentException.class, () -> new ProgramLoadResult(pcb, memory.allocateKernel(1)));
        assertThrows(IllegalArgumentException.class, () -> new ProgramLoadResult(new ProcessControlBlock(1, 33, 2), user));
        assertThrows(IllegalArgumentException.class, () -> new ProgramLoadResult(new ProcessControlBlock(1, 32, 1), user));
    }

    @Test
    void loaderOwnsRollbackAndSuppressesCleanupFailure() throws Exception {
        var memory = new MainMemory(new MemoryConfiguration(128, 32));
        var faults = MemoryFaults.install(memory, "user");
        var cleanup = new IllegalStateException("release failed");
        faults.releaseFailure = cleanup;
        var failure = assertThrows(ProgramLoadException.class, () -> new ProgramLoader()
                .loadWithAllocation(memory, 0, List.of(new LoadInstruction(RegisterName.AX))));
        assertInstanceOf(io.github.rajami1205.osimulator.model.process.exception.InvalidProcessConfigurationException.class, failure.getCause());
        assertArrayEquals(new Throwable[]{cleanup}, failure.getSuppressed());
        assertEquals(1, faults.releases);
    }
}
