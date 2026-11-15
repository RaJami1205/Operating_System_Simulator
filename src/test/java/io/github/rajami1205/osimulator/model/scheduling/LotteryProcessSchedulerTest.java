package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LotteryProcessSchedulerTest {

    /** Generador falso: entrega los tickets ganadores en el orden indicado y registra cada rango sorteado. */
    private static final class ScriptedRandom implements RandomGenerator {
        private final Deque<Integer> winningTickets = new ArrayDeque<>();
        private final List<Integer> drawnBounds = new ArrayList<>();

        ScriptedRandom(Integer... tickets) { winningTickets.addAll(List.of(tickets)); }

        @Override public int nextInt(int bound) {
            drawnBounds.add(bound);
            int ticket = winningTickets.removeFirst();
            assertTrue(ticket >= 0 && ticket < bound, "scripted ticket outside range");
            return ticket;
        }

        @Override public long nextLong() { throw new UnsupportedOperationException(); }
    }

    private final ProcessTable table = new ProcessTable(10);
    private final ReadyQueue queue = new ReadyQueue(CpuId.ZERO, table);

    private LotteryProcessScheduler scheduler(int quantum, ScriptedRandom random) {
        return new LotteryProcessScheduler(CpuId.ZERO, queue, table, quantum, random);
    }

    private ProcessControlBlock addReady(int pid, int tickets) {
        ProcessControlBlock pcb = new ProcessControlBlock(pid, 280, 5, LogicalProcessTimes.admitted(0, 0), CpuId.ZERO);
        pcb.setTickets(tickets);
        pcb.changeState(ProcessState.READY);
        table.register(pcb);
        queue.enqueue(pid);
        return pcb;
    }

    private ProcessControlBlock owner(int pid, int tickets) {
        ProcessControlBlock pcb = new ProcessControlBlock(pid, 280, 5, LogicalProcessTimes.admitted(0, 0), CpuId.ZERO);
        pcb.setTickets(tickets);
        pcb.changeState(ProcessState.RUNNING);
        table.register(pcb);
        return pcb;
    }

    @Test
    void emptyQueueSelectsNothingWithoutDrawing() {
        var random = new ScriptedRandom();
        assertTrue(scheduler(2, random).selectNext().isEmpty());
        assertTrue(random.drawnBounds.isEmpty());
    }

    @Test
    void winnerIsProportionalToTicketsOrderedByPid() {
        // Orden por PID: P1 tickets 0..2, P2 tickets 3, P3 tickets 4..9 (total 10), aunque la cola esté desordenada.
        addReady(3, 6);
        addReady(1, 3);
        addReady(2, 1);
        assertEquals(Optional.of(1), scheduler(2, new ScriptedRandom(2)).selectNext());
        assertEquals(Optional.of(2), scheduler(2, new ScriptedRandom(3)).selectNext());
        assertEquals(Optional.of(3), scheduler(2, new ScriptedRandom(4)).selectNext());
        assertEquals(Optional.of(3), scheduler(2, new ScriptedRandom(9)).selectNext());
    }

    @Test
    void selectNextKeepsItsDrawUntilDispatch() {
        addReady(1, 1);
        addReady(2, 1);
        var random = new ScriptedRandom(1, 0);
        var policy = scheduler(2, random);
        assertEquals(Optional.of(2), policy.selectNext());
        assertEquals(Optional.of(2), policy.selectNext());
        assertEquals(1, random.drawnBounds.size(), "repeated calls must not draw again");
        assertEquals(2, queue.entries().size(), "the queue is not consumed");
        policy.onDispatched(2);
        assertEquals(Optional.of(1), policy.selectNext());
    }

    @Test
    void noDrawBeforeTheQuantumBoundary() {
        var running = owner(1, 1);
        addReady(2, 1);
        var random = new ScriptedRandom();
        var policy = scheduler(2, random);
        policy.onDispatched(1);
        policy.onInstructionCompleted(1);
        assertFalse(policy.shouldPreempt(running));
        assertTrue(random.drawnBounds.isEmpty());
    }

    @Test
    void ownerThatWinsAgainKeepsRunningWithFreshQuantum() {
        var running = owner(1, 3);
        addReady(2, 1);
        // Candidatos P1 (0..2) y P2 (3): gana P1 con el ticket 0.
        var random = new ScriptedRandom(0);
        var policy = scheduler(2, random);
        policy.onDispatched(1);
        policy.onInstructionCompleted(1);
        policy.onInstructionCompleted(1);
        assertFalse(policy.shouldPreempt(running));
        assertEquals(List.of(4), random.drawnBounds, "owner tickets take part in the draw");
        policy.onInstructionCompleted(1);
        assertFalse(policy.shouldPreempt(running), "fresh quantum: no new draw yet");
        assertEquals(1, random.drawnBounds.size());
    }

    @Test
    void anotherWinnerPreemptsAndIsTheOneDispatched() {
        var running = owner(1, 1);
        addReady(2, 1);
        addReady(3, 1);
        // Candidatos P1, P2, P3: el ticket 2 corresponde a P3.
        var policy = scheduler(1, new ScriptedRandom(2));
        policy.onDispatched(1);
        policy.onInstructionCompleted(1);
        assertTrue(policy.shouldPreempt(running));
        assertEquals(Optional.of(3), policy.selectNext());
    }

    @Test
    void emptyQueueAtTheBoundaryKeepsOwnerWithoutDrawing() {
        var running = owner(1, 1);
        var random = new ScriptedRandom();
        var policy = scheduler(1, random);
        policy.onDispatched(1);
        policy.onInstructionCompleted(1);
        assertFalse(policy.shouldPreempt(running));
        assertTrue(random.drawnBounds.isEmpty());
    }

    @Test
    void ticketsAreNotSpentByWinning() {
        var winner = addReady(1, 5);
        addReady(2, 5);
        var policy = scheduler(2, new ScriptedRandom(0));
        policy.selectNext();
        assertEquals(5, winner.tickets());
    }

    @Test
    void rejectsOutOfRangeValues() {
        var pcb = new ProcessControlBlock(9, 280, 5, LogicalProcessTimes.admitted(0, 0), CpuId.ZERO);
        assertEquals(ProcessControlBlock.DEFAULT_TICKETS, pcb.tickets());
        assertThrows(IllegalArgumentException.class, () -> pcb.setTickets(0));
        assertThrows(IllegalArgumentException.class, () -> pcb.setTickets(101));
        assertThrows(IllegalArgumentException.class, () -> scheduler(0, new ScriptedRandom()));
        assertThrows(IllegalArgumentException.class, () -> scheduler(11, new ScriptedRandom()));
    }
}
