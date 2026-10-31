package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.process.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessSchedulerFactoryTest {
    @Test void createsIndependentObservationalSchedulersBoundToLocalQueues() {
        var table = new ProcessTable(10);
        var zero = new ReadyQueue(CpuId.ZERO, table);
        var one = new ReadyQueue(new CpuId(1), table);
        for (int pid = 1; pid <= 4; pid++) {
            var pcb = new ProcessControlBlock(pid, 280, 3, LogicalProcessTimes.admitted(0, 0), new CpuId(pid % 2));
            pcb.changeState(ProcessState.READY); table.register(pcb);
        }
        zero.enqueue(4); zero.enqueue(2); one.enqueue(3); one.enqueue(1);
        var a = ProcessSchedulerFactory.create(SchedulingPolicyId.FCFS, CpuId.ZERO, zero, table);
        var b = ProcessSchedulerFactory.create(SchedulingPolicyId.FCFS, new CpuId(1), one, table);
        assertNotSame(a, b);
        for (int i = 0; i < 3; i++) {
            assertEquals(Optional.of(4), a.selectNext()); assertEquals(Optional.of(3), b.selectNext());
        }
        assertEquals(List.of(4, 2), zero.entries()); assertEquals(List.of(3, 1), one.entries());
        assertTrue(table.entries().stream().allMatch(p -> p.state() == ProcessState.READY));
        table.find(4).orElseThrow().changeState(ProcessState.BLOCKED);
        assertThrows(IllegalStateException.class, a::selectNext);
        assertEquals(List.of(4, 2), zero.entries()); assertEquals(Optional.of(3), b.selectNext());
        assertThrows(IllegalArgumentException.class,
                () -> ProcessSchedulerFactory.create(SchedulingPolicyId.FCFS, CpuId.ZERO, one, table));
    }

    @Test void rejectsMissingDependencies() {
        var table = new ProcessTable(); var queue = new ReadyQueue(CpuId.ZERO, table);
        assertThrows(NullPointerException.class, () -> ProcessSchedulerFactory.create(null, CpuId.ZERO, queue, table));
        assertThrows(NullPointerException.class, () -> ProcessSchedulerFactory.create(SchedulingPolicyId.FCFS, null, queue, table));
        assertThrows(NullPointerException.class, () -> ProcessSchedulerFactory.create(SchedulingPolicyId.FCFS, CpuId.ZERO, null, table));
        assertThrows(NullPointerException.class, () -> ProcessSchedulerFactory.create(SchedulingPolicyId.FCFS, CpuId.ZERO, queue, null));
    }
}
