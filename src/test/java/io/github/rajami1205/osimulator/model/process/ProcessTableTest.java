package io.github.rajami1205.osimulator.model.process;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessTableTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {5, 10, 15, 20, 25})
    void configurableCapacityIncludesSuspendedProcessesAndReleasesSlots(int capacity) {
        var table = new ProcessTable(capacity);
        for (int pid = 1; pid <= capacity; pid++) {
            var pcb = new ProcessControlBlock(pid, 140, 1, LogicalProcessTimes.admitted(0, 0),
                    new io.github.rajami1205.osimulator.model.cpu.CpuId((pid - 1) / 5));
            pcb.changeState(pid % 2 == 0 ? ProcessState.READY_SUSPENDED : ProcessState.BLOCKED_SUSPENDED);
            table.register(pcb);
        }
        assertTrue(table.isFull());
        assertThrows(IllegalArgumentException.class, () -> table.register(new ProcessControlBlock(1, 140, 1)));
        var extra = new ProcessControlBlock(capacity + 1, 140, 1);
        assertThrows(IllegalStateException.class, () -> table.register(extra));
        assertEquals(capacity, table.size());
        table.remove(1);
        assertFalse(table.isFull());
        table.register(extra);
        assertTrue(table.isFull());
        assertSame(extra, table.find(capacity + 1).orElseThrow());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, -1})
    void rejectsNonpositiveCapacity(int capacity) {
        assertThrows(IllegalArgumentException.class, () -> new ProcessTable(capacity));
    }

    @Test
    void preservesCanonicalIdentityAdmissionOrderAndHistoricalStructuralViews() {
        var table = new ProcessTable();
        assertTrue(table.last().isEmpty());
        var first = new ProcessControlBlock(9, 32, 1);
        var second = new ProcessControlBlock(2, 33, 1);
        table.register(first);
        var historical = table.entries();
        table.register(second);
        assertSame(first, table.find(9).orElseThrow());
        assertSame(second, table.last().orElseThrow());
        assertEquals(java.util.List.of(first, second), table.entries());
        assertEquals(java.util.List.of(first), historical);
        assertThrows(UnsupportedOperationException.class, () -> historical.clear());
        assertThrows(IllegalArgumentException.class, () -> table.register(new ProcessControlBlock(9, 34, 1)));
        assertTrue(table.remove(2));
        assertFalse(table.remove(2));
        assertSame(first, table.last().orElseThrow());
        assertTrue(table.find(2).isEmpty());
    }

    @Test
    void enforcesResidentLimitAndRejectsInvalidArguments() {
        var table = new ProcessTable();
        assertThrows(NullPointerException.class, () -> table.register(null));
        assertThrows(IllegalArgumentException.class, () -> table.find(0));
        assertThrows(IllegalArgumentException.class, () -> table.remove(-1));
        for (int id = 1; id <= ProcessTable.MAX_ADMITTED_PROCESSES; id++) {
            table.register(new ProcessControlBlock(id, 32 + id, 1));
        }
        assertTrue(table.isFull());
        assertThrows(IllegalStateException.class, () -> table.register(new ProcessControlBlock(99, 50, 1)));
        assertEquals(ProcessTable.MAX_ADMITTED_PROCESSES, table.size());
        table.remove(1);
        assertFalse(table.isFull());
    }
}
