package io.github.rajami1205.osimulator.model.process;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessTableTest {
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
