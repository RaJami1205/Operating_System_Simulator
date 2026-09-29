package io.github.rajami1205.osimulator.model.cpu;

/** Immutable condition state; CMP updates equality while preserving overflow. */
public record ConditionFlags(boolean equal, boolean overflow) {
    public static final ConditionFlags CLEAR = new ConditionFlags(false, false);
}
