package io.github.rajami1205.osimulator.model.execution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CpuTickCounterTest {
    @Test void advancesOnlyExplicitlyAndRejectsOverflowWithoutChangingValue() throws Exception {
        var clock = new CpuTickCounter(); assertEquals(0, clock.current());
        clock.validateCanAdvance(); assertEquals(0, clock.current());
        clock.advance(); clock.advance(); assertEquals(2, clock.current());
        var field = CpuTickCounter.class.getDeclaredField("ticks"); field.setAccessible(true); field.setLong(clock, Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, clock::validateCanAdvance);
        assertThrows(ArithmeticException.class, clock::advance); assertEquals(Long.MAX_VALUE, clock.current());
        assertEquals(0, new CpuTickCounter().current());
    }
}
