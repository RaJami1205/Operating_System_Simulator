package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.instruction.IncInstruction;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryAllocationException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class FixedMainMemoryTest {
    @ParameterizedTest @EnumSource(UserAllocationStrategy.class)
    void dynamicStrategyCannotChangeFixedSelectionAndLoaderFailureCancels(UserAllocationStrategy strategy) {
        var m = new MainMemory(new MemoryConfiguration(128, 32), strategy, MemoryMode.FIXED_UNEQUAL,
                Optional.of(FixedPartitionConfiguration.unequal(List.of(64, 16, 16))));
        var loader = new ProgramLoader();
        assertThrows(RuntimeException.class, () -> loader.loadWithAllocation(m, 0, List.of(new IncInstruction())));
        assertTrue(m.partitions().stream().noneMatch(FixedPartition.Occupancy::occupied));
        var result = loader.loadWithAllocation(m, 1, List.of(new IncInstruction()));
        assertEquals(96, result.userAllocation().base());
        assertEquals(1, result.pcb().instructionCount());
        assertEquals(112, m.allocateUser(1).base()); // Loader directo no dejó token abierto.
    }
    private MainMemory memory(MemoryMode mode) {
        var fixed = mode == MemoryMode.FIXED_EQUAL ? FixedPartitionConfiguration.equal(2)
                : FixedPartitionConfiguration.unequal(List.of(32, 64));
        return new MainMemory(new MemoryConfiguration(128, 32), UserAllocationStrategy.NEXT_FIT, mode, Optional.of(fixed));
    }

    @ParameterizedTest @EnumSource(value = MemoryMode.class, names = {"FIXED_EQUAL", "FIXED_UNEQUAL"})
    void executablePrefixRemainsProtectedAndKernelIndependent(MemoryMode mode) {
        var m = memory(mode);
        var loaded = new ProgramLoader().loadWithAllocation(m, 1, List.of(new IncInstruction(), new IncInstruction()));
        var handle = loaded.userAllocation(); var pcb = loaded.pcb();
        assertEquals(2, handle.size()); assertEquals(2, pcb.instructionCount()); assertEquals(4096, pcb.programCounter());
        assertEquals(33, m.translateInstructionAddress(handle, pcb.logicalCodeRange(), new LogicalAddress(4097)).value());
        assertThrows(RuntimeException.class, () -> m.translateInstructionAddress(handle, pcb.logicalCodeRange(), new LogicalAddress(4098)));
        assertThrows(RuntimeException.class, () -> m.writeInstruction(handle, 2, new IncInstruction()));
        assertThrows(RuntimeException.class, () -> m.readInstruction(handle, 34));
        assertEquals(2, m.readUserBlock(handle).size());
        int capacity = m.partitions().getFirst().partition().capacity();
        for (int address = 34; address < 32 + capacity; address++) assertTrue(m.isEmpty(address));
        var other = m.allocateUser(1);
        assertEquals(32 + capacity, other.base());
        assertThrows(MemoryAllocationException.class, () -> m.allocateUser(1));
        var kernel = m.allocateKernel(8); m.allocateKernel(4); m.release(kernel);
        assertEquals(0, m.allocateKernel(8).base());
        m.release(handle); assertTrue(m.isEmpty(32)); assertTrue(m.isEmpty(33));
        assertEquals(32, m.allocateUser(capacity).base());
    }

    @ParameterizedTest @EnumSource(value = MemoryMode.class, names = {"FIXED_EQUAL", "FIXED_UNEQUAL"})
    void provisionalTokensCancelWholeOccupancyAndResetInvalidatesThem(MemoryMode mode) {
        var m = memory(mode); var foreign = memory(mode);
        var r = m.reserveUser(3); m.writeInstruction(r.allocation(), 0, new IncInstruction());
        assertThrows(IllegalStateException.class, () -> m.allocateUser(1));
        assertThrows(IllegalStateException.class, () -> m.release(r.allocation()));
        assertThrows(IllegalStateException.class, () -> foreign.confirmUser(r));
        assertThrows(IllegalStateException.class, () -> foreign.cancelUser(r));
        m.cancelUser(r); assertTrue(m.isEmpty(32)); assertFalse(m.partitions().getFirst().occupied());
        assertThrows(IllegalStateException.class, () -> m.cancelUser(r));
        var retry = m.reserveUser(3); assertNotEquals(r.allocation(), retry.allocation());
        m.confirmUser(retry); assertThrows(IllegalStateException.class, () -> m.confirmUser(retry));
        var stale = m.reserveUser(1); m.clearUserSpace();
        assertThrows(IllegalStateException.class, () -> m.cancelUser(stale));
        assertTrue(m.partitions().stream().noneMatch(FixedPartition.Occupancy::occupied));
        var staleAgain = m.reserveUser(1); m.reset();
        assertThrows(IllegalStateException.class, () -> m.confirmUser(staleAgain));
        assertEquals(32, m.allocateUser(1).base());
    }
}
