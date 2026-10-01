package io.github.rajami1205.osimulator.model.scheduling;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SuspendedReadyQueueTest {
    @Test void uniqueFifoAndImmutableHistory() {
        var queue=new SuspendedReadyQueue();assertTrue(queue.peek().isEmpty());
        assertThrows(IllegalArgumentException.class,()->queue.enqueue(0));
        queue.enqueue(3);queue.enqueue(1);var history=queue.entries();
        assertThrows(IllegalStateException.class,()->queue.enqueue(3));
        assertEquals(3,queue.peek().orElseThrow());assertTrue(queue.remove(3));
        assertFalse(queue.remove(3));queue.enqueue(3);assertEquals(List.of(1,3),queue.entries());
        assertEquals(List.of(3,1),history);assertThrows(UnsupportedOperationException.class,history::clear);
    }
}
