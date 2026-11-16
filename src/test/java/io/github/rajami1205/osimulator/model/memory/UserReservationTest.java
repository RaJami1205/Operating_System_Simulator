package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.instruction.IncInstruction;
import io.github.rajami1205.osimulator.model.process.ProcessMemoryBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class UserReservationTest {
    private MainMemory memory(UserAllocationStrategy strategy) {
        return new MainMemory(new MemoryConfiguration(128, 32), strategy);
    }

    @Test void cancellationRestoresCursorAndClearsOnlyProvisionalImage() {
        var m = memory(UserAllocationStrategy.NEXT_FIT);
        var existing = m.allocateUser(10);
        m.writeInstruction(existing, 0, new IncInstruction());
        var r = m.reserveUser(8);
        assertEquals(42, r.allocation().base());
        m.writeInstruction(r.allocation(), 0, new IncInstruction());
        m.cancelUser(r);
        assertTrue(m.isEmpty(42)); assertFalse(m.isEmpty(32));
        var retry = m.reserveUser(8);
        assertEquals(42, retry.allocation().base());
        assertNotEquals(r.allocation().allocationId(), retry.allocation().allocationId());
        assertThrows(RuntimeException.class, () -> m.validateUserAllocation(r.allocation(), new ProcessMemoryBounds(42, 8)));
        m.confirmUser(retry);
        m.release(retry.allocation());
        assertEquals(50, m.allocateUser(1).base()); // Liberación normal no revierte commit.
    }

    @Test void cancellationAfterEndNormalizationRestoresPreviousSearchAddress() {
        var m = memory(UserAllocationStrategy.NEXT_FIT);
        var old = m.allocateUser(90); m.release(old);
        var last = m.reserveUser(6);
        assertEquals(122, last.allocation().base());
        m.cancelUser(last); // La reserva había normalizado el cursor a 32.
        assertEquals(122, m.allocateUser(1).base());
        var wrapped = m.reserveUser(90);
        assertEquals(32, wrapped.allocation().base());
        m.cancelUser(wrapped);
        assertEquals(123, m.allocateUser(1).base());
    }

    @ParameterizedTest @EnumSource(UserAllocationStrategy.class)
    void rejectsForeignDuplicateAndInterleavedOperationsWithoutChangingConfirmedMemory(UserAllocationStrategy strategy) {
        var m = memory(strategy); var other = memory(strategy);
        var old = m.allocateUser(5); var r = m.reserveUser(7);
        assertThrows(IllegalStateException.class, () -> m.allocateUser(1));
        assertThrows(IllegalStateException.class, () -> m.reserveUser(1));
        assertThrows(IllegalStateException.class, () -> m.release(old));
        assertThrows(IllegalStateException.class, () -> m.release(r.allocation()));
        assertThrows(IllegalStateException.class, () -> other.confirmUser(r));
        assertThrows(IllegalStateException.class, () -> other.cancelUser(r));
        assertThrows(IllegalStateException.class, () -> m.confirmUser(null));
        var kernel = m.allocateKernel(5); m.release(kernel); // KERNEL independiente.
        m.confirmUser(r);
        assertThrows(IllegalStateException.class, () -> m.confirmUser(r));
        var later = m.reserveUser(4);
        assertThrows(IllegalStateException.class, () -> m.cancelUser(r));
        m.confirmUser(later);
        m.validateUserAllocation(later.allocation(), new ProcessMemoryBounds(later.allocation().base(), 4));
        m.release(old);
        var cancel = m.reserveUser(1); m.cancelUser(cancel);
        assertThrows(IllegalStateException.class, () -> m.cancelUser(cancel));
        assertThrows(IllegalStateException.class, () -> m.confirmUser(cancel));
    }

    @Test void resetAndClearInvalidateGenerationAndRestartCursorPreservingKernelOnClear() {
        var m = memory(UserAllocationStrategy.NEXT_FIT);
        var kernel = m.allocateKernel(5);
        m.allocateUser(10); var stale = m.reserveUser(5);
        m.clearUserSpace();
        assertThrows(IllegalStateException.class, () -> m.confirmUser(stale));
        assertThrows(IllegalStateException.class, () -> m.cancelUser(stale));
        assertEquals(32, m.allocateUser(10).base());
        m.release(kernel);
        var staleAgain = m.reserveUser(5); m.reset();
        assertThrows(IllegalStateException.class, () -> m.cancelUser(staleAgain));
        assertEquals(32, m.allocateUser(96).base());
    }

    @ParameterizedTest @EnumSource(UserAllocationStrategy.class)
    void kernelAlwaysSelectsFirstFit(UserAllocationStrategy strategy) {
        var m = memory(strategy);
        var first = m.allocateKernel(8); m.allocateKernel(4);
        var smaller = m.allocateKernel(5); m.allocateKernel(4);
        m.release(first); m.release(smaller);
        assertEquals(0, m.allocateKernel(4).base());
    }
}
