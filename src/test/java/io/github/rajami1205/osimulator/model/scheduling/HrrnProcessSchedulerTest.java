package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.*;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HrrnProcessSchedulerTest {

    private final ProcessTable table = new ProcessTable(10);
    private final ReadyQueue queue = new ReadyQueue(CpuId.ZERO, table);
    private final HrrnProcessScheduler scheduler = new HrrnProcessScheduler(CpuId.ZERO, queue, table);

    private ProcessControlBlock addReady(int pid, int burst, long arrival) {
        ProcessControlBlock pcb = new ProcessControlBlock(pid, 280, burst,
                LogicalProcessTimes.admitted(arrival, arrival), CpuId.ZERO);
        pcb.changeState(ProcessState.READY);
        table.register(pcb);
        queue.enqueue(pid);
        return pcb;
    }

    @Test
    void emptyQueueSelectsNothing() {
        scheduler.onSchedulingPoint(5);
        assertTrue(scheduler.selectNext().isEmpty());
    }

    @Test
    void picksHighestResponseRatio() {
        // t=9: C(S4,arr4) W5 -> 2.25 ; D(S5,arr6) W3 -> 1.60 ; E(S2,arr8) W1 -> 1.50
        addReady(3, 4, 4);
        addReady(4, 5, 6);
        addReady(5, 2, 8);
        scheduler.onSchedulingPoint(9);
        assertEquals(Optional.of(3), scheduler.selectNext());
    }

    @Test
    void ratioGrowsWithTimeAndChangesTheWinner() {
        // D(S5,arr6) vs E(S2,arr8): t=9 -> D 1.60 > E 1.50 ; t=13 -> E 3.50 > D 2.40
        addReady(4, 5, 6);
        addReady(5, 2, 8);
        scheduler.onSchedulingPoint(9);
        assertEquals(Optional.of(4), scheduler.selectNext());
        scheduler.onSchedulingPoint(13);
        assertEquals(Optional.of(5), scheduler.selectNext());
    }

    @Test
    void equalRatioBreaksByArrivalEvenWithHigherPid() {
        // t=8: P5 (S4,arr0,W8) -> 3.0 ; P2 (S2,arr4,W4) -> 3.0 -> gana el Arrival menor (P5).
        addReady(2, 2, 4);
        addReady(5, 4, 0);
        scheduler.onSchedulingPoint(8);
        assertEquals(Optional.of(5), scheduler.selectNext());
    }

    @Test
    void equalRatioAndArrivalBreaksByLowerPid() {
        addReady(3, 4, 0);
        addReady(2, 4, 0);
        scheduler.onSchedulingPoint(6);
        assertEquals(Optional.of(2), scheduler.selectNext());
    }

    @Test
    void serviceAlreadyReceivedReducesWaiting() {
        // t=6: P1 (S4,arr0,servicio 2) W=4 -> 2.0 ; P2 (S2,arr2) W=4 -> 3.0
        ProcessControlBlock p1 = addReady(1, 4, 0);
        p1.replaceAccounting(new ProcessAccounting(OptionalInt.of(0), Optional.of(Instant.EPOCH), 2, Optional.empty()));
        addReady(2, 2, 2);
        scheduler.onSchedulingPoint(6);
        assertEquals(Optional.of(2), scheduler.selectNext());
    }

    @Test
    void selectNextDoesNotConsumeQueue() {
        addReady(1, 3, 0);
        scheduler.onSchedulingPoint(1);
        scheduler.selectNext();
        scheduler.selectNext();
        assertEquals(1, queue.entries().size());
    }
}
