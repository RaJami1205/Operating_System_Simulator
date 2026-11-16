package io.github.rajami1205.osimulator.model.execution;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimulationClockTest {
    @Test void advancesExactlyOneSecondAndRejectsOverflowWithoutMutation() throws Exception {
        var clock = new SimulationClock();
        assertEquals(0, clock.current());
        clock.validateCanAdvance(); assertEquals(0, clock.current());
        clock.advance(); clock.advance(); assertEquals(2, clock.current());
        var seconds = SimulationClock.class.getDeclaredField("seconds");
        seconds.setAccessible(true); seconds.setLong(clock, Long.MAX_VALUE - 1);
        clock.validateCanAdvance(); clock.advance();
        assertEquals(Long.MAX_VALUE, clock.current());
        assertThrows(ArithmeticException.class, clock::validateCanAdvance);
        assertThrows(ArithmeticException.class, clock::advance);
        assertEquals(Long.MAX_VALUE, clock.current());
    }
}
