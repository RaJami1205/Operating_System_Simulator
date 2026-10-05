package io.github.rajami1205.osimulator.testing;

import java.time.*;

/** Test time advances only when explicitly requested, never as a side effect of reading it. */
public final class ControlledClock extends Clock {
    private Instant instant = Instant.parse("2026-10-01T12:00:00Z");
    public void advance(Duration duration) { instant = instant.plus(duration); }
    @Override public Instant instant() { return instant; }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
}
