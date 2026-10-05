package io.github.rajami1205.osimulator.model.job;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JobTest {
    @Test
    void validatesIdentityNameAndState() {
        assertArrayEquals(new JobState[]{JobState.PENDING, JobState.ADMITTED}, JobState.values());
        for (int id : new int[]{0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new Job(id, "p", JobState.PENDING));
        }
        assertThrows(NullPointerException.class, () -> new Job(1, null, JobState.PENDING));
        for (var name : new String[]{"", " ", "\t\n"}) {
            assertThrows(IllegalArgumentException.class, () -> new Job(1, name, JobState.PENDING));
        }
        assertThrows(NullPointerException.class, () -> new Job(1, "p", null));
        assertEquals(Integer.MAX_VALUE, new Job(Integer.MAX_VALUE, " P ", JobState.ADMITTED).jobId());
        var job = new Job(1, " P ", JobState.PENDING);
        assertEquals(" P ", job.programName());
        assertEquals(JobState.PENDING, job.state());
        assertEquals(job, new Job(1, " P ", JobState.PENDING));
    }
}
