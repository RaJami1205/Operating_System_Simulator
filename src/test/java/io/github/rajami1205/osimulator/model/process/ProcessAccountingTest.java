package io.github.rajami1205.osimulator.model.process;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessAccountingTest {
    private static final Instant START = Instant.parse("2026-10-01T12:00:00Z");
    @Test void firstInstantLaterTicksAndSingleFinishAreImmutable() {
        var initial = ProcessAccounting.initial();
        var first = initial.recordSuccessfulCpuTick(0, START);
        assertEquals(OptionalInt.of(0), first.cpuId()); assertEquals(Optional.of(START), first.startTime());
        assertEquals(1, first.cpuTicks()); assertTrue(first.finishTime().isEmpty());
        assertTrue(first.elapsedTime().isEmpty());
        var later = first.recordSuccessfulCpuTick(0, START.plusSeconds(8));
        assertEquals(2, later.cpuTicks()); assertEquals(first.startTime(), later.startTime());
        var done = later.finishAt(START.plusSeconds(90000));
        assertEquals(Optional.of(START.plusSeconds(90000)), done.finishTime()); assertEquals(2, done.cpuTicks());
        assertEquals(Duration.ofHours(25), done.elapsedTime().orElseThrow());
        assertThrows(IllegalStateException.class, () -> done.finishAt(START.plusSeconds(90000)));
        assertThrows(IllegalStateException.class, () -> done.recordSuccessfulCpuTick(0, START.plusSeconds(90001)));
        assertEquals(ProcessAccounting.initial(), initial); assertEquals(1, first.cpuTicks());
        assertTrue(initial.finishAt(START).elapsedTime().isEmpty());
        assertEquals(Duration.ZERO, first.finishAt(START).elapsedTime().orElseThrow());
    }
    @Test void invalidCpuTimesAndOverflowAreRejected() {
        var first = ProcessAccounting.initial().recordSuccessfulCpuTick(0, START);
        assertThrows(IllegalArgumentException.class, () -> first.recordSuccessfulCpuTick(1, START));
        assertThrows(IllegalArgumentException.class, () -> first.finishAt(START.minusSeconds(1)));
        assertThrows(NullPointerException.class, () -> first.finishAt(null));
        assertThrows(NullPointerException.class, () -> first.recordSuccessfulCpuTick(0, null));
        var invalidCpu = new ProcessAccounting(OptionalInt.of(1), Optional.of(START), 1, Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> invalidCpu.recordSuccessfulCpuTick(0, START));
        var full = new ProcessAccounting(OptionalInt.of(0), Optional.of(START), Long.MAX_VALUE, Optional.empty());
        assertThrows(ArithmeticException.class, () -> full.recordSuccessfulCpuTick(0, START));
        assertEquals(Long.MAX_VALUE, full.cpuTicks());
    }
}
