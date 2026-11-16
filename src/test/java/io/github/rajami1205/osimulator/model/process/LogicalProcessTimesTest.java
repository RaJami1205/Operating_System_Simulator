package io.github.rajami1205.osimulator.model.process;

import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogicalProcessTimesTest {
    @Test void validatesEventOrderAndKeepsFirstDispatchImmutable() {
        var admitted = LogicalProcessTimes.admitted(2, 3);
        var dispatched = admitted.dispatchedAt(4);
        assertSame(dispatched, dispatched.dispatchedAt(8));
        var finished = dispatched.finishedAt(9);
        assertTrue(admitted.firstDispatchTime().isEmpty()); assertTrue(dispatched.finishTime().isEmpty());
        assertEquals(OptionalLong.of(4), finished.firstDispatchTime());
        assertThrows(IllegalArgumentException.class, () -> LogicalProcessTimes.admitted(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> LogicalProcessTimes.admitted(3, 2));
        assertThrows(IllegalArgumentException.class, () -> admitted.dispatchedAt(2));
        assertThrows(IllegalArgumentException.class, () -> admitted.finishedAt(5));
        assertThrows(IllegalArgumentException.class, () -> dispatched.finishedAt(4));
        assertThrows(IllegalArgumentException.class, () -> finished.dispatchedAt(10));
        assertThrows(IllegalStateException.class, () -> finished.finishedAt(10));
    }
}
