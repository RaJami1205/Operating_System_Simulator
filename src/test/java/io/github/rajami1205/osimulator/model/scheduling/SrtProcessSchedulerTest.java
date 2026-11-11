package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class SrtProcessSchedulerTest {

    private final ProcessTable table = new ProcessTable(10);
    private final ReadyQueue queue = new ReadyQueue(CpuId.ZERO, table);
    private final SrtProcessScheduler scheduler = new SrtProcessScheduler(CpuId.ZERO, queue, table);

    // --- helpers ---

    private ProcessControlBlock addReady(int pid, int burst, long arrival) {
        ProcessControlBlock pcb = new ProcessControlBlock(pid, 280, burst,
                LogicalProcessTimes.admitted(arrival, arrival), CpuId.ZERO);
        pcb.changeState(ProcessState.READY);
        table.register(pcb);
        queue.enqueue(pid);
        return pcb;
    }

    private ProcessControlBlock addRunning(int pid, int burst, long arrival, int completedTicks) {
        ProcessControlBlock pcb = new ProcessControlBlock(pid, 280, burst,
                LogicalProcessTimes.admitted(arrival, arrival), CpuId.ZERO);
        pcb.changeState(ProcessState.RUNNING);
        table.register(pcb);
        ProcessAccounting accountingWithTicks = new ProcessAccounting(
                OptionalInt.of(0), Optional.of(Instant.EPOCH), completedTicks, Optional.empty());
        pcb.replaceAccounting(accountingWithTicks);
        return pcb;
    }

    // --- selectNext: selección por menor remaining ---

    @Test
    void selectsProcessWithLowestRemaining() {
        // P1: burst=5, service=0, remaining=5
        // P2: burst=4, service=2, remaining=2  ← menor
        // P3: burst=9, service=0, remaining=9
        ProcessControlBlock p2 = addReady(2, 4, 1);
        ProcessAccounting accountingP2 = new ProcessAccounting(
                OptionalInt.of(0), Optional.of(Instant.EPOCH), 2, Optional.empty());
        p2.replaceAccounting(accountingP2);
        addReady(1, 5, 0);
        addReady(3, 9, 2);

        Optional<Integer> selected = scheduler.selectNext();

        assertEquals(Optional.of(2), selected);
    }

    @Test
    void selectsLowestRemainingNotLowestBurst() {
        // P1: burst=3, service=2, remaining=1  ← menor remaining (aunque burst>P2)
        // P2: burst=2, service=0, remaining=2
        ProcessControlBlock p1 = addReady(1, 3, 0);
        ProcessAccounting accountingP1 = new ProcessAccounting(
                OptionalInt.of(0), Optional.of(Instant.EPOCH), 2, Optional.empty());
        p1.replaceAccounting(accountingP1);
        addReady(2, 2, 1);

        Optional<Integer> selected = scheduler.selectNext();

        assertEquals(Optional.of(1), selected);
    }

    @Test
    void tieInRemainingBreaksByArrivalThenPid() {
        // P3: burst=4, service=1, remaining=3, arrival=5
        // P2: burst=3, service=0, remaining=3, arrival=1  ← mismo remaining, menor arrival
        // P4: burst=5, service=2, remaining=3, arrival=1  ← mismo remaining, mismo arrival, mayor PID
        ProcessControlBlock p3 = addReady(3, 4, 5);
        ProcessAccounting accountingP3 = new ProcessAccounting(
                OptionalInt.of(0), Optional.of(Instant.EPOCH), 1, Optional.empty());
        p3.replaceAccounting(accountingP3);
        addReady(2, 3, 1);
        ProcessControlBlock p4 = addReady(4, 5, 1);
        ProcessAccounting accountingP4 = new ProcessAccounting(
                OptionalInt.of(0), Optional.of(Instant.EPOCH), 2, Optional.empty());
        p4.replaceAccounting(accountingP4);

        assertEquals(Optional.of(2), scheduler.selectNext());
        queue.remove(2);
        assertEquals(Optional.of(4), scheduler.selectNext());
        queue.remove(4);
        assertEquals(Optional.of(3), scheduler.selectNext());
    }

    @Test
    void emptyQueueReturnsEmpty() {
        Optional<Integer> selected = scheduler.selectNext();
        assertTrue(selected.isEmpty());
    }

    @Test
    void doesNotConsumeQueueOnRepeatedCalls() {
        addReady(1, 5, 0);
        addReady(2, 2, 1);

        for (int i = 0; i < 3; i++) {
            assertEquals(Optional.of(2), scheduler.selectNext());
        }
        assertEquals(List.of(1, 2), queue.entries());
    }

    @ParameterizedTest
    @EnumSource(value = ProcessState.class, names = "READY", mode = EnumSource.Mode.EXCLUDE)
    void nonReadyCandidateThrowsWithoutMutation(ProcessState state) {
        addReady(1, 2, 0);
        addReady(2, 5, 0);
        table.find(2).orElseThrow().changeState(state);

        assertThrows(IllegalStateException.class, scheduler::selectNext);
        assertEquals(List.of(1, 2), queue.entries());
    }

    // --- shouldPreempt ---

    @Test
    void preemptsWhenCandidateHasStrictlyLessRemaining() {
        // Owner: burst=8, service=1, remaining=7
        // Candidato P2: burst=4, service=0, remaining=4  ← 4 < 7 → preemptar
        ProcessControlBlock owner = addRunning(1, 8, 0, 1);
        addReady(2, 4, 1);

        boolean result = scheduler.shouldPreempt(owner);

        assertTrue(result);
    }

    @Test
    void doesNotPreemptOnEqualRemaining() {
        // Owner: burst=5, service=1, remaining=4
        // Candidato P2: burst=4, service=0, remaining=4  ← empate → no expulsar
        ProcessControlBlock owner = addRunning(1, 5, 0, 1);
        addReady(2, 4, 1);

        boolean result = scheduler.shouldPreempt(owner);

        assertFalse(result);
    }

    @Test
    void doesNotPreemptWhenOwnerHasLessRemaining() {
        // Owner: burst=3, service=0, remaining=3
        // Candidato P2: burst=5, service=0, remaining=5  ← 5 > 3 → no expulsar
        ProcessControlBlock owner = addRunning(1, 3, 0, 0);
        addReady(2, 5, 1);

        boolean result = scheduler.shouldPreempt(owner);

        assertFalse(result);
    }

    @Test
    void doesNotPreemptWhenQueueIsEmpty() {
        ProcessControlBlock owner = addRunning(1, 5, 0, 2);

        boolean result = scheduler.shouldPreempt(owner);

        assertFalse(result);
    }

    @Test
    void remainingNeverGoesBelowZero() {
        // Service supera el burst (posible con loops): remaining = max(0, burst-service) = 0
        // Owner con remaining=0, candidato también con remaining=0 → empate → no preemptar
        ProcessControlBlock owner = addRunning(1, 3, 0, 5);
        ProcessControlBlock candidate = addReady(2, 3, 1);
        ProcessAccounting accountingWithLoops = new ProcessAccounting(
                OptionalInt.of(0), Optional.of(Instant.EPOCH), 10, Optional.empty());
        candidate.replaceAccounting(accountingWithLoops);

        boolean result = scheduler.shouldPreempt(owner);

        assertFalse(result);
    }

    // --- construcción ---

    @Test
    void requiresNonNullDependencies() {
        assertThrows(NullPointerException.class, () -> new SrtProcessScheduler(null, queue, table));
        assertThrows(NullPointerException.class, () -> new SrtProcessScheduler(CpuId.ZERO, null, table));
        assertThrows(NullPointerException.class, () -> new SrtProcessScheduler(CpuId.ZERO, queue, null));
    }

    @Test
    void requiresQueueBoundToSameCpu() {
        assertThrows(IllegalArgumentException.class,
                () -> new SrtProcessScheduler(new CpuId(1), queue, table));
    }

    @Test
    void shouldPreemptRejectsNullOwner() {
        assertThrows(NullPointerException.class, () -> scheduler.shouldPreempt(null));
    }
}
