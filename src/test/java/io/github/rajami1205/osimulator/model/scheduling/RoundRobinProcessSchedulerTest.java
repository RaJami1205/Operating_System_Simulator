package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoundRobinProcessSchedulerTest {

    private final ProcessTable table = new ProcessTable(10);
    private final ReadyQueue queue = new ReadyQueue(CpuId.ZERO, table);
    private final RoundRobinProcessScheduler scheduler = new RoundRobinProcessScheduler(CpuId.ZERO, queue, table, 2);

    private ProcessControlBlock addReady(int pid) {
        ProcessControlBlock pcb = new ProcessControlBlock(pid, 280, 5,
                LogicalProcessTimes.admitted(0, 0), CpuId.ZERO);
        pcb.changeState(ProcessState.READY);
        table.register(pcb);
        queue.enqueue(pid);
        return pcb;
    }

    private ProcessControlBlock owner(int pid) {
        ProcessControlBlock pcb = new ProcessControlBlock(pid, 280, 5,
                LogicalProcessTimes.admitted(0, 0), CpuId.ZERO);
        pcb.changeState(ProcessState.RUNNING);
        table.register(pcb);
        return pcb;
    }

    @Test
    void selectsFifoHeadWithoutConsumingIt() {
        addReady(3);
        addReady(1);
        assertEquals(java.util.Optional.of(3), scheduler.selectNext());
        assertEquals(java.util.Optional.of(3), scheduler.selectNext());
    }

    @Test
    void emptyQueueSelectsNothing() {
        assertTrue(scheduler.selectNext().isEmpty());
    }

    @Test
    void preemptsExactlyWhenQuantumIsConsumedAndSomeoneWaits() {
        ProcessControlBlock running = owner(1);
        addReady(2);
        scheduler.onDispatched(1);
        assertFalse(scheduler.shouldPreempt(running));
        scheduler.onInstructionCompleted(1);
        assertFalse(scheduler.shouldPreempt(running));
        scheduler.onInstructionCompleted(1);
        assertTrue(scheduler.shouldPreempt(running));
    }

    @Test
    void doesNotPreemptWithEmptyQueueAndStartsFreshQuantum() {
        ProcessControlBlock running = owner(1);
        scheduler.onDispatched(1);
        scheduler.onInstructionCompleted(1);
        scheduler.onInstructionCompleted(1);
        assertFalse(scheduler.shouldPreempt(running));
        addReady(2);
        // El quantum se reinició al expirar sin competencia.
        assertFalse(scheduler.shouldPreempt(running));
        scheduler.onInstructionCompleted(1);
        scheduler.onInstructionCompleted(1);
        assertTrue(scheduler.shouldPreempt(running));
    }

    @Test
    void dispatchGivesFreshQuantum() {
        ProcessControlBlock running = owner(1);
        addReady(2);
        scheduler.onDispatched(1);
        scheduler.onInstructionCompleted(1);
        scheduler.onDispatched(1);
        scheduler.onInstructionCompleted(1);
        assertFalse(scheduler.shouldPreempt(running));
    }

    @Test
    void rejectsQuantumOutOfRange() {
        assertThrows(IllegalArgumentException.class, () -> new RoundRobinProcessScheduler(CpuId.ZERO, queue, table, 0));
        assertThrows(IllegalArgumentException.class, () -> new RoundRobinProcessScheduler(CpuId.ZERO, queue, table, 11));
    }
}
