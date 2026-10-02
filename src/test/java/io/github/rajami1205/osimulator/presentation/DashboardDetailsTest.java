package io.github.rajami1205.osimulator.presentation;

import io.github.rajami1205.osimulator.model.process.ProcessAccounting;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DashboardDetailsTest {
    @Test void realLocalTimestampsAndTotalElapsedHoursAreRendered() {
        var start = Instant.parse("2026-10-01T12:00:00Z");
        var accounting = ProcessAccounting.initial().recordSuccessfulCpuTick(0, start)
                .finishAt(start.plus(Duration.ofHours(27)).plusSeconds(122));
        var text = DashboardDetails.accounting(accounting, ZoneOffset.ofHours(-6));
        assertTrue(text.contains("Start Time: 2026-10-01 06:00:00 AM"));
        assertTrue(text.contains("Finish Time: 2026-10-02 09:02:02 AM"));
        assertTrue(text.contains("Elapsed Time: 27:02:02"));
        assertTrue(text.contains("CPU Ticks: 1"));
        var pm = DashboardDetails.accounting(accounting, ZoneOffset.UTC);
        assertTrue(pm.contains("Start Time: 2026-10-01 12:00:00 PM"));
    }
    @Test void absentTimestampsAndElapsedAreExplicit() {
        var text = DashboardDetails.accounting(ProcessAccounting.initial(), ZoneOffset.UTC);
        assertTrue(text.contains("Start Time: —")); assertTrue(text.contains("Finish Time: —"));
        assertTrue(text.contains("Elapsed Time: —"));
    }
}
