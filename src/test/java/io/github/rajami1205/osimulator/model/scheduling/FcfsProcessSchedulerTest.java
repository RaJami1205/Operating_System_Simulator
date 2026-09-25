package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.process.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class FcfsProcessSchedulerTest {
    private final ReadyQueue queue = new ReadyQueue();
    private final ProcessTable table = new ProcessTable();
    private final ProcessScheduler scheduler = new FcfsProcessScheduler(queue, table);

    private ProcessControlBlock register(int pid, int size, int priority) {
        var pcb = new ProcessControlBlock(pid, 32, size);
        pcb.changeState(ProcessState.READY);
        pcb.setPriority(priority);
        table.register(pcb);
        return pcb;
    }

    @Test
    void selectsQueueOrderWithoutConsumingOrMutatingCanonicalState() {
        var second = register(2, 1, 100);
        var first = register(9, 20, -100);
        first.setProgramCounter(3);
        first.setNextPcbAddress(Optional.of(new PcbAddress(5)));
        var context = first.cpuContext();
        var accounting = first.accounting();
        queue.enqueue(9);
        queue.enqueue(2);
        for (int i = 0; i < 3; i++) assertEquals(Optional.of(9), scheduler.selectNext());
        first.setPriority(999);
        second.setPriority(-999);
        assertEquals(Optional.of(9), scheduler.selectNext());
        assertEquals(List.of(9, 2), queue.entries());
        assertEquals(ProcessState.READY, first.state());
        assertEquals(ProcessState.READY, second.state());
        assertSame(context, first.cpuContext());
        assertSame(accounting, first.accounting());
        assertEquals(3, first.programCounter());
        assertEquals(Optional.of(new PcbAddress(5)), first.nextPcbAddress());
        queue.poll();
        queue.enqueue(9);
        assertEquals(Optional.of(2), scheduler.selectNext());
    }

    @Test
    void emptyQueueIsNormalEvenIfTableContainsReadyProcesses() {
        register(1, 1, 0);
        assertTrue(scheduler.selectNext().isEmpty());
    }

    @Test
    void missingHeadFailsWithoutSkippingOrRepairing() {
        register(2, 1, 0);
        queue.enqueue(9);
        queue.enqueue(2);
        assertThrows(IllegalStateException.class, scheduler::selectNext);
        assertEquals(List.of(9, 2), queue.entries());
    }

    @ParameterizedTest
    @EnumSource(value = ProcessState.class, names = "READY", mode = EnumSource.Mode.EXCLUDE)
    void nonReadyHeadFailsWithoutMutation(ProcessState state) {
        var first = register(9, 1, 0);
        register(2, 1, 0);
        queue.enqueue(9);
        queue.enqueue(2);
        first.changeState(state);
        assertThrows(IllegalStateException.class, scheduler::selectNext);
        assertEquals(state, first.state());
        assertEquals(List.of(9, 2), queue.entries());
    }

    @Test
    void requiresBothSharedDependencies() {
        assertThrows(NullPointerException.class, () -> new FcfsProcessScheduler(null, table));
        assertThrows(NullPointerException.class, () -> new FcfsProcessScheduler(queue, null));
    }
}
