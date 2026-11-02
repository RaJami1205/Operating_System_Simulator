package io.github.rajami1205.osimulator.model.memory;

import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClockReplacementTest {
    final ClockReplacement clock = new ClockReplacement(3);
    final List<FrameHandle> handles = java.util.stream.IntStream.range(0, 3)
            .mapToObj(i -> new FrameHandle(UUID.randomUUID(), new Frame(i, 140 + i * 4, 4))).toList();
    @Test void secondChanceIsProvisionalAndCommitAdvancesHandWithNewIdentity() {
        assertEquals(0, clock.snapshot().hand()); handles.forEach(clock::reference);
        var before = clock.snapshot(); var plan = clock.prepare(handles).orElseThrow();
        assertEquals(handles.getFirst(), plan.victim()); assertSame(before, clock.snapshot());
        var next = new FrameHandle(UUID.randomUUID(), plan.victim().frame());
        clock.commit(clock.prepareCommit(plan, next));
        assertEquals(1, clock.snapshot().hand()); assertFalse(clock.snapshot().references().containsKey(plan.victim()));
        assertTrue(clock.snapshot().references().get(next));
        assertFalse(clock.snapshot().references().get(handles.get(1))); assertFalse(clock.snapshot().references().get(handles.get(2)));
        assertThrows(UnsupportedOperationException.class, () -> before.references().clear());
    }
    @Test void zeroBitWinsAndWraparoundSkipsIneligibleFrames() {
        handles.forEach(clock::reference);
        var plan = clock.prepare(handles).orElseThrow();
        var first = new FrameHandle(UUID.randomUUID(), handles.getFirst().frame());
        clock.commit(clock.prepareCommit(plan, first));
        clock.reference(handles.get(1));
        var second = clock.prepare(List.of(handles.get(1), handles.get(2))).orElseThrow();
        assertEquals(handles.get(2), second.victim());
        clock.commit(clock.prepareCommit(second, new FrameHandle(UUID.randomUUID(), handles.get(2).frame())));
        assertEquals(0, clock.snapshot().hand()); assertFalse(clock.snapshot().references().get(handles.get(1)));
    }
    @Test void emptyEligibilityIsBoundedAndDoesNotConsumeSecondChances() {
        handles.forEach(clock::reference); var before = clock.snapshot();
        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertTrue(clock.prepare(List.of()).isEmpty()));
        assertSame(before, clock.snapshot());
    }
    @Test void releaseKeepsHandAndResetClearsMetadata() {
        handles.forEach(clock::reference); var plan = clock.prepare(handles).orElseThrow();
        var next = new FrameHandle(UUID.randomUUID(), handles.getFirst().frame());
        clock.commit(clock.prepareCommit(plan, next)); clock.release(List.of(next));
        assertEquals(1, clock.snapshot().hand()); assertFalse(clock.snapshot().references().containsKey(next));
        assertThrows(IllegalStateException.class, () -> clock.prepare(List.of(next)));
        clock.reset(); assertEquals(new ClockReplacement.View(0, Map.of()), clock.snapshot());
    }
    @Test void planCannotCommitAfterMetadataChangesOrWithAnOldIdentity() {
        handles.forEach(clock::reference); var plan = clock.prepare(handles).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> clock.prepareCommit(plan, plan.victim()));
        clock.reference(handles.getFirst());
        assertThrows(IllegalStateException.class, () -> clock.prepareCommit(plan, new FrameHandle(UUID.randomUUID(), plan.victim().frame())));
        assertThrows(IllegalStateException.class, () -> clock.prepare(List.of(handles.getFirst(), handles.getFirst())));
    }
}
