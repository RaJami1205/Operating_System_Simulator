package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AddressTranslationTest {
    private final MainMemory memory = new MainMemory(new MemoryConfiguration(128, 32));
    private final LogicalCodeRange range = new LogicalCodeRange(new LogicalAddress(100), 3);

    @Test void translatesFirstMiddleAndLastWithoutReadingOrMutatingContent() {
        var allocation = memory.allocateUser(3);
        for (int offset = 0; offset < 3; offset++) {
            assertEquals(new PhysicalAddress(32 + offset), memory.translateInstructionAddress(
                    allocation, range, new LogicalAddress(100 + offset)));
            assertTrue(memory.isEmpty(32 + offset));
        }
        var highRange = new LogicalCodeRange(new LogicalAddress(Integer.MAX_VALUE - 3), 3);
        assertEquals(new PhysicalAddress(34), memory.translateInstructionAddress(
                allocation, highRange, new LogicalAddress(Integer.MAX_VALUE - 1)));
    }

    @Test void rejectsNullsMismatchedLengthAndNonExecutableAddresses() {
        var allocation = memory.allocateUser(3);
        assertThrows(NullPointerException.class, () -> memory.translateInstructionAddress(null, range, range.base()));
        assertThrows(NullPointerException.class, () -> memory.translateInstructionAddress(allocation, null, range.base()));
        assertThrows(NullPointerException.class, () -> memory.translateInstructionAddress(allocation, range, null));
        assertThrows(MemoryProtectionException.class, () -> memory.translateInstructionAddress(
                allocation, new LogicalCodeRange(range.base(), 2), range.base()));
        for (int address : new int[]{0, 99, 103, 104, Integer.MAX_VALUE}) {
            assertThrows(MemoryProtectionException.class, () -> memory.translateInstructionAddress(
                    allocation, range, new LogicalAddress(address)));
        }
    }

    @Test void rejectsKernelForeignAndAlteredHandles() {
        var allocation = memory.allocateUser(3);
        var foreign = new MainMemory(new MemoryConfiguration(128, 32)).allocateUser(3);
        for (var invalid : List.of(memory.allocateKernel(3), foreign,
                new MemoryAllocation(UUID.randomUUID(), MemoryRegion.USER, allocation.base(), 3),
                new MemoryAllocation(allocation.allocationId(), MemoryRegion.USER, allocation.base() + 1, 3),
                new MemoryAllocation(allocation.allocationId(), MemoryRegion.USER, allocation.base(), 2),
                new MemoryAllocation(allocation.allocationId(), MemoryRegion.KERNEL, allocation.base(), 3))) {
            assertThrows(MemoryProtectionException.class,
                    () -> memory.translateInstructionAddress(invalid, range, range.base()));
        }
        assertEquals(new PhysicalAddress(32), memory.translateInstructionAddress(allocation, range, range.base()));
    }

    @Test void relocationUsesNewHandleAndRejectsOldIdentityEvenAfterRangeReuse() {
        var original = memory.allocateUser(3);
        var address = new LogicalAddress(101);
        assertEquals(new PhysicalAddress(33), memory.translateInstructionAddress(original, range, address));
        memory.release(original);
        assertThrows(MemoryProtectionException.class, () -> memory.translateInstructionAddress(original, range, address));
        var reused = memory.allocateUser(3);
        assertEquals(original.base(), reused.base());
        assertNotEquals(original.allocationId(), reused.allocationId());
        var relocated = memory.allocateUser(3);
        assertEquals(new PhysicalAddress(36), memory.translateInstructionAddress(relocated, range, address));
        assertEquals(new PhysicalAddress(33), memory.translateInstructionAddress(reused, range, address));
        assertThrows(MemoryProtectionException.class, () -> memory.translateInstructionAddress(original, range, address));
        memory.clearUserSpace();
        assertThrows(MemoryProtectionException.class, () -> memory.translateInstructionAddress(relocated, range, address));
        var current = memory.allocateUser(3);
        memory.reset();
        assertThrows(MemoryProtectionException.class, () -> memory.translateInstructionAddress(current, range, address));
    }
}
