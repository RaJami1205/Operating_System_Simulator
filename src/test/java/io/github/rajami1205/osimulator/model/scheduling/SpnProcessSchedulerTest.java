package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class SpnProcessSchedulerTest {
    private final ProcessTable table = new ProcessTable(10);
    private final ReadyQueue queue = new ReadyQueue(CpuId.ZERO, table);
    private final ProcessScheduler scheduler = new SpnProcessScheduler(CpuId.ZERO, queue, table);

    private ProcessControlBlock ready(int pid, int burst, long arrival, CpuId cpu) {
        var pcb = new ProcessControlBlock(pid, 280, burst, LogicalProcessTimes.admitted(arrival, arrival), cpu);
        pcb.changeState(ProcessState.READY);
        table.register(pcb);
        return pcb;
    }

    private void enqueue(int pid, int burst, long arrival) {
        ready(pid, burst, arrival, CpuId.ZERO);
        queue.enqueue(pid);
    }

    @Test
    void selectsShortestDeclaredBurstWithoutConsumingOrMutating() {
        enqueue(1, 5, 0);
        enqueue(2, 2, 3);
        enqueue(3, 4, 1);
        var chosen = table.find(2).orElseThrow();
        var context = chosen.cpuContext();
        for (int i = 0; i < 3; i++) assertEquals(Optional.of(2), scheduler.selectNext());
        assertEquals(List.of(1, 2, 3), queue.entries());
        assertTrue(table.entries().stream().allMatch(p -> p.state() == ProcessState.READY));
        assertSame(context, chosen.cpuContext());
        queue.remove(2);
        assertEquals(Optional.of(3), scheduler.selectNext());
    }

    @Test
    void ignoresPriorityAndQueueOrder() {
        enqueue(1, 9, 0);
        enqueue(2, 1, 0);
        table.find(1).orElseThrow().setPriority(-100);
        table.find(2).orElseThrow().setPriority(100);
        assertEquals(Optional.of(2), scheduler.selectNext());
    }

    @Test
    void equalBurstBreaksTieByArrivalThenLowerPid() {
        enqueue(4, 3, 5);
        enqueue(3, 3, 1);
        enqueue(2, 3, 1);
        assertEquals(Optional.of(2), scheduler.selectNext());
        queue.remove(2);
        assertEquals(Optional.of(3), scheduler.selectNext());
        queue.remove(3);
        assertEquals(Optional.of(4), scheduler.selectNext());
    }

    @Test
    void emptyQueueIsNormalEvenIfTableContainsReadyProcesses() {
        ready(1, 1, 0, CpuId.ZERO);
        assertTrue(scheduler.selectNext().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = ProcessState.class, names = "READY", mode = EnumSource.Mode.EXCLUDE)
    void anyNonReadyCandidateFailsWithoutMutation(ProcessState state) {
        enqueue(1, 1, 0);
        enqueue(2, 7, 0);
        table.find(2).orElseThrow().changeState(state);
        assertThrows(IllegalStateException.class, scheduler::selectNext);
        assertEquals(state, table.find(2).orElseThrow().state());
        assertEquals(List.of(1, 2), queue.entries());
    }

    @Test
    void unknownOrForeignCandidatesFailInsteadOfBeingSkipped() {
        var looseTable = new ProcessTable(10);
        var looseQueue = new ReadyQueue();
        var loose = new SpnProcessScheduler(CpuId.ZERO, looseQueue, looseTable);
        looseQueue.enqueue(9);
        assertThrows(IllegalStateException.class, loose::selectNext);
        var foreign = new ProcessControlBlock(9, 280, 1, LogicalProcessTimes.admitted(0, 0), new CpuId(1));
        foreign.changeState(ProcessState.READY);
        looseTable.register(foreign);
        assertThrows(IllegalStateException.class, loose::selectNext);
        assertEquals(List.of(9), looseQueue.entries());
    }

    @Test
    void requiresDependenciesBoundToTheSameCpuAndTable() {
        assertThrows(NullPointerException.class, () -> new SpnProcessScheduler(null, queue, table));
        assertThrows(NullPointerException.class, () -> new SpnProcessScheduler(CpuId.ZERO, null, table));
        assertThrows(NullPointerException.class, () -> new SpnProcessScheduler(CpuId.ZERO, queue, null));
        assertThrows(IllegalArgumentException.class, () -> new SpnProcessScheduler(new CpuId(1), queue, table));
    }
}
