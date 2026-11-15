package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GuaranteedProcessSchedulerTest {

    private final ProcessTable table = new ProcessTable(10);
    private final ReadyQueue queue = new ReadyQueue(CpuId.ZERO, table);
    private final GuaranteedProcessScheduler scheduler = new GuaranteedProcessScheduler(CpuId.ZERO, queue, table);

    // --- helpers ---

    private ProcessControlBlock addReady(int pid, long arrival, int service) {
        ProcessControlBlock pcb = newPcb(pid, arrival, service);
        pcb.changeState(ProcessState.READY);
        table.register(pcb);
        queue.enqueue(pid);
        return pcb;
    }

    private ProcessControlBlock addRunning(int pid, long arrival, int service) {
        ProcessControlBlock pcb = newPcb(pid, arrival, service);
        pcb.changeState(ProcessState.RUNNING);
        table.register(pcb);
        return pcb;
    }

    private static ProcessControlBlock newPcb(int pid, long arrival, int service) {
        ProcessControlBlock pcb = new ProcessControlBlock(pid, 280, 9,
                LogicalProcessTimes.admitted(arrival, arrival), CpuId.ZERO);
        ProcessAccounting accounting = new ProcessAccounting(
                OptionalInt.of(0), Optional.of(Instant.EPOCH), service, Optional.empty());
        pcb.replaceAccounting(accounting);
        return pcb;
    }

    // --- selectNext ---

    @Test
    void selectsLowestServiceOverElapsedRatio() {
        scheduler.onSchedulingPoint(10);
        addReady(1, 0, 5);   // 5/10 = 0.50
        addReady(2, 4, 2);   // 2/6  = 0.33  <- menor
        addReady(3, 2, 6);   // 6/8  = 0.75

        assertEquals(Optional.of(2), scheduler.selectNext());
    }

    @Test
    void unservedProcessHasRatioZeroEvenWhenJustArrived() {
        scheduler.onSchedulingPoint(10);
        addReady(1, 0, 1);   // 1/10 = 0.10
        addReady(2, 10, 0);  // recién llegado, servicio 0 -> ratio 0

        assertEquals(Optional.of(2), scheduler.selectNext());
    }

    @Test
    void unservedTieBreaksByArrivalThenPid() {
        scheduler.onSchedulingPoint(5);
        addReady(3, 1, 0);
        addReady(1, 3, 0);
        addReady(2, 1, 0);

        assertEquals(Optional.of(2), scheduler.selectNext());
    }

    @Test
    void ratioTieBreaksByArrival() {
        scheduler.onSchedulingPoint(8);
        addReady(1, 4, 1);   // 1/4 = 0.25
        addReady(2, 0, 2);   // 2/8 = 0.25, llegó antes

        assertEquals(Optional.of(2), scheduler.selectNext());
    }

    @Test
    void emptyQueueSelectsNothing() {
        scheduler.onSchedulingPoint(3);

        assertEquals(Optional.empty(), scheduler.selectNext());
    }

    @Test
    void doesNotConsumeQueue() {
        scheduler.onSchedulingPoint(4);
        addReady(1, 0, 2);
        addReady(2, 0, 1);

        assertEquals(Optional.of(2), scheduler.selectNext());
        assertEquals(Optional.of(2), scheduler.selectNext());
        assertEquals(List.of(1, 2), queue.entries());
    }

    @Test
    void rejectsQueuedProcessThatIsNotReady() {
        scheduler.onSchedulingPoint(2);
        ProcessControlBlock pcb = addReady(1, 0, 0);
        pcb.changeState(ProcessState.BLOCKED);

        assertThrows(IllegalStateException.class, scheduler::selectNext);
    }

    // --- shouldPreempt ---

    @Test
    void preemptsWhenCandidateHasStrictlyLowerRatio() {
        scheduler.onSchedulingPoint(6);
        ProcessControlBlock owner = addRunning(1, 0, 4);   // 4/6
        addReady(2, 0, 2);                                 // 2/6

        assertTrue(scheduler.shouldPreempt(owner));
    }

    @Test
    void unservedCandidatePreemptsServedOwner() {
        scheduler.onSchedulingPoint(2);
        ProcessControlBlock owner = addRunning(1, 0, 2);
        addReady(2, 2, 0);

        assertTrue(scheduler.shouldPreempt(owner));
    }

    @Test
    void keepsOwnerOnRatioTie() {
        scheduler.onSchedulingPoint(4);
        ProcessControlBlock owner = addRunning(1, 0, 2);   // 2/4 = 0.5
        addReady(2, 2, 1);                                 // 1/2 = 0.5

        assertFalse(scheduler.shouldPreempt(owner));
    }

    @Test
    void keepsOwnerWhenCandidateHasHigherRatio() {
        scheduler.onSchedulingPoint(4);
        ProcessControlBlock owner = addRunning(1, 0, 1);   // 1/4
        addReady(2, 2, 2);                                 // 2/2

        assertFalse(scheduler.shouldPreempt(owner));
    }

    @Test
    void keepsUnservedOwnerAgainstUnservedCandidate() {
        scheduler.onSchedulingPoint(1);
        ProcessControlBlock owner = addRunning(1, 0, 0);
        addReady(2, 1, 0);

        assertFalse(scheduler.shouldPreempt(owner));
    }

    @Test
    void neverPreemptsWithEmptyQueue() {
        scheduler.onSchedulingPoint(5);
        ProcessControlBlock owner = addRunning(1, 0, 5);

        assertFalse(scheduler.shouldPreempt(owner));
    }

    @Test
    void rejectsForeignQueueBinding() {
        var foreignQueue = new ReadyQueue(new CpuId(1), table);

        assertThrows(IllegalArgumentException.class,
                () -> new GuaranteedProcessScheduler(CpuId.ZERO, foreignQueue, table));
    }
}
