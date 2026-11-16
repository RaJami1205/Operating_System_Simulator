package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.configuration.PriorityConfiguration;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PriorityProcessSchedulerTest {

    private final ProcessTable table = new ProcessTable(10);
    private final ReadyQueue queue = new ReadyQueue(CpuId.ZERO, table);

    private PriorityProcessScheduler scheduler(boolean preemptive, int agingInterval) {
        return new PriorityProcessScheduler(CpuId.ZERO, queue, table,
                new PriorityConfiguration(preemptive, agingInterval));
    }

    private ProcessControlBlock addReady(int pid, int priority, long arrival) {
        ProcessControlBlock pcb = new ProcessControlBlock(pid, 280, 3,
                LogicalProcessTimes.admitted(arrival, arrival), CpuId.ZERO);
        pcb.setPriority(priority);
        pcb.changeState(ProcessState.READY);
        table.register(pcb);
        queue.enqueue(pid);
        return pcb;
    }

    private ProcessControlBlock owner(int pid, int priority) {
        ProcessControlBlock pcb = new ProcessControlBlock(pid, 280, 3,
                LogicalProcessTimes.admitted(0, 0), CpuId.ZERO);
        pcb.setPriority(priority);
        pcb.changeState(ProcessState.RUNNING);
        table.register(pcb);
        return pcb;
    }

    @Test
    void emptyQueueSelectsNothing() {
        var policy = scheduler(false, 0);
        policy.onSchedulingPoint(0);
        assertTrue(policy.selectNext().isEmpty());
    }

    @Test
    void lowerNumberMeansHigherPriority() {
        var policy = scheduler(false, 0);
        addReady(1, 5, 0);
        addReady(2, 1, 0);
        addReady(3, 9, 0);
        policy.onSchedulingPoint(0);
        assertEquals(Optional.of(2), policy.selectNext());
    }

    @Test
    void tieBreaksByArrivalThenPid() {
        var policy = scheduler(false, 0);
        addReady(4, 2, 3);
        addReady(3, 2, 1);
        addReady(2, 2, 1);
        policy.onSchedulingPoint(5);
        assertEquals(Optional.of(2), policy.selectNext());
    }

    @Test
    void nonPreemptiveNeverExpelsTheOwner() {
        var policy = scheduler(false, 0);
        ProcessControlBlock running = owner(1, 9);
        addReady(2, 0, 0);
        policy.onSchedulingPoint(1);
        assertFalse(policy.shouldPreempt(running));
    }

    @Test
    void preemptiveExpelsOnlyOnStrictlyBetterPriority() {
        var policy = scheduler(true, 0);
        ProcessControlBlock running = owner(1, 3);
        addReady(2, 3, 0);
        policy.onSchedulingPoint(1);
        assertFalse(policy.shouldPreempt(running), "tie keeps the current owner");
        addReady(3, 2, 1);
        assertTrue(policy.shouldPreempt(running));
    }

    @Test
    void agingImprovesEffectivePriorityEveryNTicksWithFloorZero() {
        var policy = scheduler(false, 3);
        ProcessControlBlock waiting = addReady(1, 4, 0);
        policy.onSchedulingPoint(0);
        assertEquals(4, policy.effectivePriority(waiting));
        policy.onSchedulingPoint(2);
        assertEquals(4, policy.effectivePriority(waiting));
        policy.onSchedulingPoint(3);
        assertEquals(3, policy.effectivePriority(waiting));
        policy.onSchedulingPoint(9);
        assertEquals(1, policy.effectivePriority(waiting));
        policy.onSchedulingPoint(30);
        assertEquals(0, policy.effectivePriority(waiting));
        assertEquals(4, waiting.priority(), "the PCB keeps the base priority");
    }

    @Test
    void agingLetsALongWaitingProcessWinOverANewArrival() {
        var policy = scheduler(false, 3);
        addReady(1, 3, 0);
        policy.onSchedulingPoint(0);
        addReady(2, 0, 9);
        policy.onSchedulingPoint(9);
        // P1: 3 - 9/3 = 0 ; P2: 0 -> empate, gana el Arrival menor (P1).
        assertEquals(Optional.of(1), policy.selectNext());
    }

    @Test
    void dispatchResetsAccumulatedAging() {
        var policy = scheduler(false, 2);
        ProcessControlBlock pcb = addReady(1, 5, 0);
        policy.onSchedulingPoint(0);
        policy.onSchedulingPoint(6);
        assertEquals(2, policy.effectivePriority(pcb));
        policy.onDispatched(1);
        queue.remove(1);
        // Vuelve a READY más tarde: la espera empieza de cero.
        queue.enqueue(1);
        policy.onSchedulingPoint(10);
        assertEquals(5, policy.effectivePriority(pcb));
        policy.onSchedulingPoint(12);
        assertEquals(4, policy.effectivePriority(pcb));
    }

    @Test
    void agingOffKeepsBasePriority() {
        var policy = scheduler(false, 0);
        ProcessControlBlock pcb = addReady(1, 7, 0);
        policy.onSchedulingPoint(0);
        policy.onSchedulingPoint(100);
        assertEquals(7, policy.effectivePriority(pcb));
    }

    @Test
    void selectNextDoesNotConsumeQueue() {
        var policy = scheduler(false, 0);
        addReady(1, 1, 0);
        policy.onSchedulingPoint(0);
        policy.selectNext();
        policy.selectNext();
        assertEquals(1, queue.entries().size());
    }

    @Test
    void configurationRejectsAgingOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new PriorityConfiguration(false, -1));
        assertThrows(IllegalArgumentException.class, () -> new PriorityConfiguration(false, 51));
        assertEquals(new PriorityConfiguration(false, 0), PriorityConfiguration.defaults());
    }
}
