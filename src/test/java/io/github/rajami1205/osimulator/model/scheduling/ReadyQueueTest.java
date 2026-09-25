package io.github.rajami1205.osimulator.model.scheduling;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReadyQueueTest {
    @Test
    void fifoPeekPollRemovalAndReentryPreserveArrivalOrder() {
        var queue = new ReadyQueue();
        assertTrue(queue.peek().isEmpty());
        assertTrue(queue.poll().isEmpty());
        for (int pid : new int[]{9, 2, 7}) queue.enqueue(pid);
        var history = queue.entries();
        assertEquals(List.of(9, 2, 7), history);
        assertThrows(UnsupportedOperationException.class, history::clear);
        assertEquals(Optional.of(9), queue.peek());
        assertEquals(Optional.of(9), queue.peek());
        assertEquals(history, queue.entries());
        assertEquals(Optional.of(9), queue.poll());
        assertTrue(queue.remove(2));
        assertFalse(queue.remove(2));
        queue.enqueue(9);
        queue.enqueue(2);
        assertEquals(List.of(7, 9, 2), queue.entries());
        assertEquals(List.of(9, 2, 7), history);
        assertEquals(Optional.of(7), queue.poll());
        assertEquals(Optional.of(9), queue.poll());
        assertEquals(Optional.of(2), queue.poll());
        assertTrue(queue.poll().isEmpty());
    }

    @Test
    void rejectsInvalidAndDuplicateIdsWithoutMutationAndHasNoResidentLimit() {
        var queue = new ReadyQueue();
        for (int invalid : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> queue.enqueue(invalid));
            assertThrows(IllegalArgumentException.class, () -> queue.remove(invalid));
        }
        for (int pid = 1; pid <= 8; pid++) queue.enqueue(pid);
        var before = queue.entries();
        assertThrows(IllegalStateException.class, () -> queue.enqueue(3));
        assertEquals(before, queue.entries());
    }
}
