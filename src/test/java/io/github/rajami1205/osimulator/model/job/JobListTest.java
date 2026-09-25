package io.github.rajami1205.osimulator.model.job;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JobListTest {
    @Test
    void preservesInsertionOrderAndAllowsMoreThanFiveJobsForOneProgram() {
        var jobs = new JobList();
        for (int id : new int[]{7, 2, 9, 1, 6, 3}) jobs.add(new Job(id, "same", JobState.PENDING));
        assertEquals(List.of(7, 2, 9, 1, 6, 3), jobs.entries().stream().map(Job::jobId).toList());
        var history = jobs.entries();
        assertThrows(UnsupportedOperationException.class, history::clear);
        assertThrows(IllegalArgumentException.class, () -> jobs.add(new Job(2, "different", JobState.ADMITTED)));
        assertEquals(history, jobs.entries());
        assertEquals("same", jobs.find(2).orElseThrow().programName());
        assertTrue(jobs.find(99).isEmpty());
        assertTrue(jobs.remove(2));
        assertFalse(jobs.remove(2));
        assertEquals(6, history.size());
        jobs.add(new Job(2, "new", JobState.PENDING));
        assertEquals(List.of(7, 9, 1, 6, 3, 2), jobs.entries().stream().map(Job::jobId).toList());
    }

    @Test
    void invalidOperationsPreserveState() {
        var jobs = new JobList();
        assertThrows(NullPointerException.class, () -> jobs.add(null));
        for (int id : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> jobs.find(id));
            assertThrows(IllegalArgumentException.class, () -> jobs.remove(id));
        }
        assertTrue(jobs.entries().isEmpty());
    }
}
