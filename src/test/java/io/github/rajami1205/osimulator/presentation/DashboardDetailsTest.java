package io.github.rajami1205.osimulator.presentation;

import io.github.rajami1205.osimulator.model.process.ProcessAccounting;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
        assertTrue(text.contains("Duration (s): 97322"));
        assertTrue(text.contains("CPU Ticks: 1"));
        var pm = DashboardDetails.accounting(accounting, ZoneOffset.UTC);
        assertTrue(pm.contains("Start Time: 2026-10-01 12:00:00 PM"));
    }
    @Test void absentTimestampsAndElapsedAreExplicit() {
        var text = DashboardDetails.accounting(ProcessAccounting.initial(), ZoneOffset.UTC);
        assertTrue(text.contains("Start Time: —")); assertTrue(text.contains("Finish Time: —"));
        assertTrue(text.contains("Elapsed Time: —"));
        assertTrue(text.endsWith("Duration (s): —"));
    }
    @ParameterizedTest
    @CsvSource({"5, 00:00:05", "420, 00:07:00", "5400, 01:30:00", "90000, 25:00:00", "0, 00:00:00"})
    void totalRealSecondsFollowElapsedWithoutWrappingOrUsingCpuTicks(long seconds, String elapsed) {
        var start = Instant.parse("2026-10-01T12:00:00Z");
        var accounting = ProcessAccounting.initial().recordSuccessfulCpuTick(0, start).finishAt(start.plusSeconds(seconds));
        var text = DashboardDetails.accounting(accounting, ZoneOffset.UTC);
        assertTrue(text.endsWith("Elapsed Time: " + elapsed + "\nDuration (s): " + seconds));
        assertTrue(text.contains("CPU Ticks: 1"));
    }
    @Test void fractionalSecondsAreTruncated() {
        var start = Instant.parse("2026-10-01T12:00:00Z");
        var accounting = ProcessAccounting.initial().recordSuccessfulCpuTick(0, start)
                .finishAt(start.plusMillis(5999));
        assertTrue(DashboardDetails.accounting(accounting, ZoneOffset.UTC)
                .endsWith("Elapsed Time: 00:00:05\nDuration (s): 5"));
    }
    @Test void eitherMissingTimestampLeavesDurationAbsent() {
        var instant = Instant.parse("2026-10-01T12:00:00Z");
        for (var accounting : List.of(
                ProcessAccounting.initial().recordSuccessfulCpuTick(0, instant),
                new ProcessAccounting(OptionalInt.empty(), Optional.empty(), 0, Optional.of(instant)))) {
            assertTrue(DashboardDetails.accounting(accounting, ZoneOffset.UTC)
                    .endsWith("Elapsed Time: —\nDuration (s): —"));
        }
    }
}
