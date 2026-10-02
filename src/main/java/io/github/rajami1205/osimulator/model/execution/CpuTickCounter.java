package io.github.rajami1205.osimulator.model.execution;

/** Session-local count of successful CPU ticks; independent of host time. */
public final class CpuTickCounter {
    private long ticks;
    public long current() { return ticks; }
    /** Preflight before executing an instruction that may have observable effects. */
    public void validateCanAdvance() { Math.incrementExact(ticks); }
    public void advance() { ticks = Math.incrementExact(ticks); }
}
