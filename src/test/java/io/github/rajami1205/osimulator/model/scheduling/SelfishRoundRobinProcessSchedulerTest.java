package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SelfishRoundRobinProcessSchedulerTest {
    @Test void discardedRotationLeavesAcceptedRoundAndOwnerUntouched() throws Exception {
        var policy = scheduler(1, 2, 1);
        arrive(1, 0);
        arrive(2, 0);
        policy.onSchedulingPoint(0);
        dispatch(policy, 1);
        complete(policy, 1, 1);
        assertTrue(policy.shouldPreempt(pcb(1)));
        policy.preparePreemptionCommit(pcb(1)); // Discard this attempt.
        var round = policy.getClass().getDeclaredField("acceptedWaiting");
        round.setAccessible(true);
        var owner = policy.getClass().getDeclaredField("ownerPid");
        owner.setAccessible(true);
        assertEquals(1, owner.get(policy));
        assertEquals(List.of(2), List.copyOf((java.util.Deque<?>) round.get(policy)));
        assertTrue(policy.shouldPreempt(pcb(1)));
        preempt(policy, 1);
        assertNull(owner.get(policy));
        assertEquals(List.of(2, 1), List.copyOf((java.util.Deque<?>) round.get(policy)));
    }

    private final ProcessTable table = new ProcessTable(10);
    private final ReadyQueue queue = new ReadyQueue(CpuId.ZERO, table);

    // --- helpers: simulan lo que hacen Dispatcher y runtime alrededor del scheduler ---

    private SelfishRoundRobinProcessScheduler scheduler(int quantum, int newRate, int acceptedRate) {
        return new SelfishRoundRobinProcessScheduler(CpuId.ZERO, queue, table, quantum, newRate, acceptedRate);
    }

    private void arrive(int pid, long arrival) {
        ProcessControlBlock pcb = new ProcessControlBlock(pid, 280, 9,
                LogicalProcessTimes.admitted(arrival, arrival), CpuId.ZERO);
        pcb.changeState(ProcessState.READY);
        table.register(pcb);
        queue.enqueue(pid);
    }

    private void dispatch(SelfishRoundRobinProcessScheduler scheduler, int pid) {
        queue.remove(pid);
        pcb(pid).changeState(ProcessState.RUNNING);
        scheduler.onDispatched(pid);
    }

    private void preempt(SelfishRoundRobinProcessScheduler scheduler, int pid) {
        var confirm = scheduler.preparePreemptionCommit(pcb(pid));
        pcb(pid).changeState(ProcessState.READY);
        queue.enqueue(pid);
        confirm.run();
    }

    private void complete(SelfishRoundRobinProcessScheduler scheduler, int pid, int instructions) {
        for (int i = 0; i < instructions; i++) {
            scheduler.onInstructionCompleted(pid);
        }
    }

    private ProcessControlBlock pcb(int pid) {
        return table.find(pid).orElseThrow();
    }

    // --- aceptación ---

    @Test
    void firstProcessIsAcceptedWhenThereAreNoAcceptedProcesses() {
        var scheduler = scheduler(2, 2, 1);
        arrive(1, 0);

        scheduler.onSchedulingPoint(0);

        assertEquals(Optional.of(1), scheduler.selectNext());
    }

    @Test
    void newProcessWaitsUntilItsPriorityReachesTheLowestAccepted() {
        var scheduler = scheduler(2, 2, 1);
        arrive(1, 0);
        scheduler.onSchedulingPoint(0);
        dispatch(scheduler, 1);
        arrive(2, 3);

        // A (aceptado) = 1·t ; B (nuevo) = 2·(t − 3)
        scheduler.onSchedulingPoint(3);   // A 3, B 0
        assertEquals(Optional.empty(), scheduler.selectNext());
        scheduler.onSchedulingPoint(4);   // A 4, B 2
        assertEquals(Optional.empty(), scheduler.selectNext());
        scheduler.onSchedulingPoint(5);   // A 5, B 4
        assertEquals(Optional.empty(), scheduler.selectNext());
        scheduler.onSchedulingPoint(6);   // A 6, B 6 -> aceptado
        assertEquals(Optional.of(2), scheduler.selectNext());
    }

    @Test
    void zeroAcceptedRateAcceptsNewProcessesImmediately() {
        var scheduler = scheduler(2, 2, 0);
        arrive(1, 0);
        scheduler.onSchedulingPoint(0);
        dispatch(scheduler, 1);
        arrive(2, 3);

        scheduler.onSchedulingPoint(3);   // A 0, B 0

        assertEquals(Optional.of(2), scheduler.selectNext());
    }

    @Test
    void blockedAcceptedProcessesDoNotCountForAcceptance() {
        var scheduler = scheduler(2, 2, 1);
        arrive(1, 0);
        scheduler.onSchedulingPoint(0);
        dispatch(scheduler, 1);
        pcb(1).changeState(ProcessState.BLOCKED);
        arrive(2, 3);

        scheduler.onSchedulingPoint(3);   // A (prio 3) bloqueado no compite -> B entra con prio 0

        assertEquals(Optional.of(2), scheduler.selectNext());
    }

    @Test
    void newProcessesAreEvaluatedByArrivalThenPid() {
        var scheduler = scheduler(1, 2, 0);
        arrive(3, 0);
        arrive(2, 0);
        arrive(1, 1);

        // t=1: se evalúan por Arrival y PID -> 2 (entra: no hay aceptados, prio 2), 3 (prio 2 >= 2),
        // 1 (prio 0 < 2, espera). Ronda [2, 3].
        scheduler.onSchedulingPoint(1);

        assertEquals(Optional.of(2), scheduler.selectNext());
        dispatch(scheduler, 2);
        complete(scheduler, 2, 1);
        scheduler.onSchedulingPoint(2);   // 1 llega a prio 2 -> aceptado al final: ronda [3, 1]
        assertTrue(scheduler.shouldPreempt(pcb(2)));
        preempt(scheduler, 2);
        assertEquals(Optional.of(3), scheduler.selectNext());
        dispatch(scheduler, 3);
        complete(scheduler, 3, 1);
        scheduler.onSchedulingPoint(3);
        assertTrue(scheduler.shouldPreempt(pcb(3)));
        preempt(scheduler, 3);
        assertEquals(Optional.of(1), scheduler.selectNext());
    }

    // --- ronda RR de aceptados ---

    @Test
    void newProcessNotYetAcceptedDoesNotPreemptAndQuantumRestarts() {
        var scheduler = scheduler(2, 2, 1);
        arrive(1, 0);
        scheduler.onSchedulingPoint(0);
        dispatch(scheduler, 1);
        complete(scheduler, 1, 1);
        arrive(2, 1);

        scheduler.onSchedulingPoint(1);   // A 1, B 0: B no aceptado
        assertFalse(scheduler.shouldPreempt(pcb(1)));
        complete(scheduler, 1, 1);
        scheduler.onSchedulingPoint(2);   // A 2, B 2 -> aceptado; quantum de A agotado
        assertTrue(scheduler.shouldPreempt(pcb(1)));
    }

    @Test
    void ownerKeepsRunningWithFreshQuantumWhenNoAcceptedWaits() {
        var scheduler = scheduler(2, 2, 1);
        arrive(1, 0);
        scheduler.onSchedulingPoint(0);
        dispatch(scheduler, 1);
        complete(scheduler, 1, 2);

        scheduler.onSchedulingPoint(2);
        assertFalse(scheduler.shouldPreempt(pcb(1)));
        complete(scheduler, 1, 1);
        scheduler.onSchedulingPoint(3);
        assertFalse(scheduler.shouldPreempt(pcb(1)));   // quantum nuevo: sólo 1 de 2
    }

    @Test
    void preemptedOwnerGoesToTailAfterThoseAcceptedBeforeItsExpiry() {
        var scheduler = scheduler(1, 2, 1);
        arrive(1, 0);
        arrive(2, 0);
        scheduler.onSchedulingPoint(0);   // A y B aceptados con prio 0
        dispatch(scheduler, 1);
        complete(scheduler, 1, 1);
        arrive(3, 1);

        scheduler.onSchedulingPoint(1);   // C 0 < A 1, B 1 -> no aceptado
        assertTrue(scheduler.shouldPreempt(pcb(1)));
        preempt(scheduler, 1);
        assertEquals(Optional.of(2), scheduler.selectNext());   // ronda [B, A]
        dispatch(scheduler, 2);
        complete(scheduler, 2, 1);

        scheduler.onSchedulingPoint(2);   // C 2 = A 2 = B 2 -> C aceptado: ronda [A, C]
        assertTrue(scheduler.shouldPreempt(pcb(2)));   // ronda [A, C, B]
        preempt(scheduler, 2);
        assertEquals(Optional.of(1), scheduler.selectNext());
        dispatch(scheduler, 1);
        complete(scheduler, 1, 1);

        scheduler.onSchedulingPoint(3);
        assertTrue(scheduler.shouldPreempt(pcb(1)));
        preempt(scheduler, 1);
        assertEquals(Optional.of(3), scheduler.selectNext());
    }

    @Test
    void acceptedProcessReturningFromBlockRejoinsTheRoundWithoutAcceptance() {
        var scheduler = scheduler(1, 2, 1);
        arrive(1, 0);
        scheduler.onSchedulingPoint(0);
        dispatch(scheduler, 1);
        complete(scheduler, 1, 1);
        pcb(1).changeState(ProcessState.BLOCKED);
        arrive(2, 1);
        scheduler.onSchedulingPoint(1);   // B entra: A bloqueado no compite
        dispatch(scheduler, 2);
        complete(scheduler, 2, 1);

        pcb(1).changeState(ProcessState.READY);
        queue.enqueue(1);
        scheduler.onSchedulingPoint(2);   // A ya aceptado: vuelve a la ronda directamente

        assertTrue(scheduler.shouldPreempt(pcb(2)));
        preempt(scheduler, 2);
        assertEquals(Optional.of(1), scheduler.selectNext());
    }

    @Test
    void selectNextIsIdempotentAndDoesNotConsumeTheQueue() {
        var scheduler = scheduler(2, 2, 1);
        arrive(1, 0);
        arrive(2, 0);
        scheduler.onSchedulingPoint(0);

        assertEquals(Optional.of(1), scheduler.selectNext());
        assertEquals(Optional.of(1), scheduler.selectNext());
        assertEquals(List.of(1, 2), queue.entries());
    }

    @Test
    void fixedRatesAreTwoAndOne() {
        assertEquals(2, SelfishRoundRobinProcessScheduler.NEW_RATE);
        assertEquals(1, SelfishRoundRobinProcessScheduler.ACCEPTED_RATE);
    }

    @Test
    void rejectsInvalidConstruction() {
        var foreignQueue = new ReadyQueue(new CpuId(1), table);

        assertThrows(IllegalArgumentException.class,
                () -> new SelfishRoundRobinProcessScheduler(CpuId.ZERO, queue, table, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SelfishRoundRobinProcessScheduler(CpuId.ZERO, queue, table, 11));
        assertThrows(IllegalArgumentException.class,
                () -> new SelfishRoundRobinProcessScheduler(CpuId.ZERO, foreignQueue, table, 2));
        assertThrows(IllegalArgumentException.class, () -> scheduler(2, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> scheduler(2, 2, -1));
    }
}
