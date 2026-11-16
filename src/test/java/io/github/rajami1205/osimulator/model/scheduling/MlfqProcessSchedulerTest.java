package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MlfqProcessSchedulerTest {
    @Test void discardedDemotionCanRetryAtOriginalLevelAndQuantum() {
        arrive(1);
        arrive(2);
        point();
        dispatch(1);
        complete(1, 1);
        assertTrue(scheduler.shouldPreempt(pcb(1)));
        scheduler.preparePreemptionCommit(pcb(1)); // Dispatcher failed before commit.
        assertTrue(scheduler.shouldPreempt(pcb(1)), "one completed instruction still expires level zero");
        preempt(1);
        assertEquals(Optional.of(2), scheduler.selectNext());
        dispatch(2);
        complete(2, 1);
        assertEquals(Optional.of(1), pointAndPreempt(2));
        dispatch(1);
        complete(1, 1);
        assertFalse(scheduler.shouldPreempt(pcb(1)), "confirmed demotion starts a level-one quantum");
    }

    private final ProcessTable table = new ProcessTable(10);
    private final ReadyQueue queue = new ReadyQueue(CpuId.ZERO, table);
    private final MlfqProcessScheduler scheduler = new MlfqProcessScheduler(CpuId.ZERO, queue, table);
    private long now;

    // --- helpers: simulan lo que hacen Dispatcher y runtime alrededor del scheduler ---

    private void arrive(int pid) {
        ProcessControlBlock pcb = new ProcessControlBlock(pid, 280, 20,
                LogicalProcessTimes.admitted(now, now), CpuId.ZERO);
        pcb.changeState(ProcessState.READY);
        table.register(pcb);
        queue.enqueue(pid);
    }

    private void point() {
        scheduler.onSchedulingPoint(now);
        now++;
    }

    private void dispatch(int pid) {
        queue.remove(pid);
        pcb(pid).changeState(ProcessState.RUNNING);
        scheduler.onDispatched(pid);
    }

    private void preempt(int pid) {
        var confirm = scheduler.preparePreemptionCommit(pcb(pid));
        pcb(pid).changeState(ProcessState.READY);
        queue.enqueue(pid);
        confirm.run();
    }

    private void complete(int pid, int instructions) {
        for (int i = 0; i < instructions; i++) {
            scheduler.onInstructionCompleted(pid);
        }
    }

    /** Punto de planificación + expulsión del owner; devuelve el siguiente elegido. */
    private Optional<Integer> pointAndPreempt(int owner) {
        point();
        assertTrue(scheduler.shouldPreempt(pcb(owner)));
        preempt(owner);
        return scheduler.selectNext();
    }

    private ProcessControlBlock pcb(int pid) {
        return table.find(pid).orElseThrow();
    }

    @Test
    void levelQuantaAreOneTwoFour() {
        assertEquals(List.of(1, 2, 4), MlfqProcessScheduler.LEVEL_QUANTA);
    }

    @Test
    void newProcessEntersLevelZeroAndHigherLevelRunsFirst() {
        arrive(1);
        point();
        dispatch(1);
        complete(1, 1);
        arrive(2);

        // A agotó el quantum del nivel 0 y hay otro READY: baja a nivel 1; B (nivel 0) gana.
        assertEquals(Optional.of(2), pointAndPreempt(1));
    }

    @Test
    void ownerAloneKeepsItsLevelWithFreshQuantum() {
        arrive(1);
        point();
        dispatch(1);
        complete(1, 1);
        point();
        assertFalse(scheduler.shouldPreempt(pcb(1)));   // solo: no se degrada

        arrive(2);
        complete(1, 1);
        // Sigue en nivel 0 (quantum 1): una sola instrucción más ya agota el quantum.
        assertEquals(Optional.of(2), pointAndPreempt(1));
    }

    @Test
    void preemptedProcessGoesToTailOfNextLevel() {
        arrive(1);
        arrive(2);
        point();
        dispatch(1);
        complete(1, 1);
        assertEquals(Optional.of(2), pointAndPreempt(1));   // A -> nivel 1
        dispatch(2);
        complete(2, 1);
        assertEquals(Optional.of(1), pointAndPreempt(2));   // B -> nivel 1 detrás de A

        dispatch(1);
        complete(1, 1);
        point();
        assertFalse(scheduler.shouldPreempt(pcb(1)));       // nivel 1: quantum 2, va por 1
        complete(1, 1);
        assertEquals(Optional.of(2), pointAndPreempt(1));   // A -> nivel 2; B (nivel 1) gana
    }

    @Test
    void higherLevelArrivalWaitsForTheQuantumBoundary() {
        arrive(1);
        arrive(2);
        point();
        dispatch(1);
        complete(1, 1);
        pointAndPreempt(1);                                 // A -> nivel 1
        dispatch(2);
        complete(2, 1);
        pointAndPreempt(2);                                 // B -> nivel 1: ronda [A, B]
        dispatch(1);
        complete(1, 1);
        arrive(3);                                          // C entra a nivel 0

        point();
        assertFalse(scheduler.shouldPreempt(pcb(1)));       // A va por 1 de 2: C espera
        complete(1, 1);
        assertEquals(Optional.of(3), pointAndPreempt(1));
    }

    @Test
    void demotedOwnerThatIsStillTheBestKeepsRunning() {
        arrive(1);
        arrive(2);
        point();
        dispatch(1);
        complete(1, 1);
        pointAndPreempt(1);                                 // A -> 1
        dispatch(2);
        complete(2, 1);
        pointAndPreempt(2);                                 // B -> 1
        dispatch(1);
        complete(1, 2);
        pointAndPreempt(1);                                 // A -> 2
        dispatch(2);
        complete(2, 2);
        arrive(3);
        assertEquals(Optional.of(3), pointAndPreempt(2));   // B -> 2; C (nivel 0) gana
        dispatch(3);
        complete(3, 1);

        point();
        // C baja a nivel 1, pero A y B esperan en nivel 2: C sigue con quantum nuevo (2).
        assertFalse(scheduler.shouldPreempt(pcb(3)));
        complete(3, 1);
        point();
        assertFalse(scheduler.shouldPreempt(pcb(3)));
        complete(3, 1);
        assertEquals(Optional.of(1), pointAndPreempt(3));   // C -> 2 detrás de A y B
    }

    @Test
    void processReturningFromBlockKeepsItsLevel() {
        arrive(1);
        arrive(2);
        point();
        dispatch(1);
        complete(1, 1);
        pointAndPreempt(1);                                 // A -> nivel 1
        dispatch(2);
        pcb(2).changeState(ProcessState.TERMINATED);
        point();
        dispatch(1);
        pcb(1).changeState(ProcessState.BLOCKED);           // page fault a mitad de quantum
        point();

        pcb(1).changeState(ProcessState.READY);
        queue.enqueue(1);                                   // A vuelve antes que C llegue
        arrive(3);
        point();

        // Si A volviera a nivel 0 iría primero; conserva el nivel 1, así que C (nivel 0) gana.
        assertEquals(Optional.of(3), scheduler.selectNext());
    }

    @Test
    void selectNextIsIdempotentAndDoesNotConsumeTheQueue() {
        arrive(1);
        arrive(2);
        point();

        assertEquals(Optional.of(1), scheduler.selectNext());
        assertEquals(Optional.of(1), scheduler.selectNext());
        assertEquals(List.of(1, 2), queue.entries());
    }

    @Test
    void emptyQueueSelectsNothingAndRejectsForeignBinding() {
        point();
        assertEquals(Optional.empty(), scheduler.selectNext());

        var foreignQueue = new ReadyQueue(new CpuId(1), table);
        assertThrows(IllegalArgumentException.class, () -> new MlfqProcessScheduler(CpuId.ZERO, foreignQueue, table));
    }
}
