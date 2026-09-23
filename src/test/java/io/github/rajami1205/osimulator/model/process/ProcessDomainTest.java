package io.github.rajami1205.osimulator.model.process;

import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.cpu.exception.InvalidRegisterValueException;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.process.exception.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class ProcessDomainTest {
    @ParameterizedTest
    @CsvSource({"-1,1", "0,0", "0,-1", "2147483647,1", "2147483646,2"})
    void boundsRejectInvalidOrOverflowingRanges(int base, int limit) {
        assertThrows(InvalidProcessConfigurationException.class, () -> new ProcessMemoryBounds(base, limit));
    }

    @Test
    void boundsAndAddressesKeepTheirFullAddressRange() {
        assertEquals(1, new ProcessMemoryBounds(0, 1).endExclusive());
        assertEquals(Integer.MAX_VALUE, new ProcessMemoryBounds(Integer.MAX_VALUE - 1, 1).endExclusive());
        assertEquals(0, new PcbAddress(0).address());
        assertEquals(Integer.MAX_VALUE, new PcbAddress(Integer.MAX_VALUE).address());
        assertThrows(IllegalArgumentException.class, () -> new PcbAddress(-1));
    }

    @Test
    void stackIsBoundedLifoAndFailuresAreAtomic() {
        var stack = new ProcessStack();
        assertTrue(stack.isEmpty());
        assertThrows(ProcessStackUnderflowException.class, stack::pop);
        for (int value : new int[]{-32768, 32767, 0, 1, 2}) stack.push(value);
        assertEquals(5, stack.size());
        assertTrue(stack.isFull());
        var saved = stack.values();
        assertThrows(ProcessStackOverflowException.class, () -> stack.push(3));
        assertEquals(saved, stack.values());
        assertThrows(UnsupportedOperationException.class, () -> saved.add(4));
        for (int value : new int[]{2, 1, 0, 32767, -32768}) assertEquals(value, stack.pop());
        assertEquals(5, saved.size());
        assertTrue(stack.isEmpty());
        assertFalse(stack.isFull());
        for (int invalid : new int[]{-32769, 32768}) {
            assertThrows(InvalidRegisterValueException.class, () -> stack.push(invalid));
            assertTrue(stack.isEmpty());
        }
    }

    @Test
    void accountingStartsEmptyAndValidatesTimes() {
        var initial = ProcessAccounting.initial();
        assertTrue(initial.cpuId().isEmpty());
        assertTrue(initial.startTime().isEmpty());
        assertEquals(0, initial.cpuTime());
        assertTrue(initial.finishTime().isEmpty());
        assertDoesNotThrow(() -> new ProcessAccounting(OptionalInt.of(0), OptionalLong.of(5), 0, OptionalLong.of(5)));
        assertDoesNotThrow(() -> new ProcessAccounting(OptionalInt.empty(), OptionalLong.empty(), Long.MAX_VALUE, OptionalLong.of(Long.MAX_VALUE)));
        assertThrows(IllegalArgumentException.class, () -> new ProcessAccounting(OptionalInt.empty(), OptionalLong.of(-1), 0, OptionalLong.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProcessAccounting(OptionalInt.empty(), OptionalLong.empty(), -1, OptionalLong.empty()));
        assertThrows(IllegalArgumentException.class, () -> new ProcessAccounting(OptionalInt.empty(), OptionalLong.empty(), 0, OptionalLong.of(-1)));
        assertThrows(IllegalArgumentException.class, () -> new ProcessAccounting(OptionalInt.empty(), OptionalLong.of(5), 0, OptionalLong.of(4)));
        assertThrows(NullPointerException.class, () -> new ProcessAccounting(null, OptionalLong.empty(), 0, OptionalLong.empty()));
        assertThrows(NullPointerException.class, () -> new ProcessAccounting(OptionalInt.empty(), null, 0, OptionalLong.empty()));
        assertThrows(NullPointerException.class, () -> new ProcessAccounting(OptionalInt.empty(), OptionalLong.empty(), 0, null));
    }

    @Test
    void openFilesAreLogicalUniqueAndEncapsulated() {
        var table = new OpenFileTable();
        assertEquals(0, table.size());
        assertTrue(table.open("data"));
        assertFalse(table.open("data"));
        assertTrue(table.contains("data"));
        var saved = table.files();
        assertThrows(UnsupportedOperationException.class, () -> saved.clear());
        assertTrue(table.close("data"));
        assertFalse(table.close("data"));
        assertEquals(Set.of("data"), saved);
        for (String invalid : new String[]{"", "   "}) {
            assertThrows(IllegalArgumentException.class, () -> table.open(invalid));
            assertThrows(IllegalArgumentException.class, () -> table.close(invalid));
            assertThrows(IllegalArgumentException.class, () -> table.contains(invalid));
        }
        assertThrows(NullPointerException.class, () -> table.open(null));
        assertThrows(NullPointerException.class, () -> table.close(null));
        assertThrows(NullPointerException.class, () -> table.contains(null));
        assertEquals(0, table.size());
    }

    @Test
    void pcbOwnsComponentsAndPreservesContextThroughCompatibilityPcUpdates() {
        var pcb = new ProcessControlBlock(1, 100, 5);
        var other = new ProcessControlBlock(2, 200, 5);
        assertEquals(CpuContext.initial(), pcb.cpuContext());
        assertEquals(new ProcessMemoryBounds(100, 5), pcb.memoryBounds());
        assertEquals(ProcessAccounting.initial(), pcb.accounting());
        assertEquals(0, pcb.priority());
        assertTrue(pcb.nextPcbAddress().isEmpty());
        pcb.stack().push(42);
        pcb.openFiles().open("file");
        assertTrue(other.stack().isEmpty());
        assertEquals(0, other.openFiles().size());
        var context = new CpuContext<Instruction>(100, Optional.of(new LoadInstruction(RegisterName.AX)),
                1, 2, 3, 4, 5, 6, 7, new ConditionFlags(true, true));
        pcb.replaceCpuContext(context);
        pcb.setProgramCounter(105);
        assertEquals(context.withProgramCounter(105), pcb.cpuContext());
        assertEquals(100, context.programCounter());
        assertEquals(ProcessState.NEW, pcb.state());
        var before = pcb.cpuContext();
        assertThrows(InvalidProcessProgramCounterException.class, () -> pcb.replaceCpuContext(context.withProgramCounter(99)));
        assertThrows(InvalidProcessProgramCounterException.class, () -> pcb.replaceCpuContext(context.withProgramCounter(106)));
        assertThrows(InvalidProcessProgramCounterException.class, () -> pcb.setProgramCounter(0));
        assertThrows(NullPointerException.class, () -> pcb.replaceCpuContext(null));
        assertEquals(before, pcb.cpuContext());
        pcb.setPriority(Integer.MIN_VALUE);
        assertEquals(Integer.MIN_VALUE, pcb.priority());
        pcb.setPriority(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, pcb.priority());
        pcb.setNextPcbAddress(Optional.of(new PcbAddress(10)));
        assertThrows(NullPointerException.class, () -> pcb.setNextPcbAddress(null));
        assertEquals(Optional.of(new PcbAddress(10)), pcb.nextPcbAddress());
        pcb.setNextPcbAddress(Optional.empty());
        assertTrue(pcb.nextPcbAddress().isEmpty());
        var accounting = new ProcessAccounting(OptionalInt.of(0), OptionalLong.of(1), 2, OptionalLong.of(4));
        pcb.replaceAccounting(accounting);
        assertThrows(NullPointerException.class, () -> pcb.replaceAccounting(null));
        assertEquals(accounting, pcb.accounting());
    }
}
