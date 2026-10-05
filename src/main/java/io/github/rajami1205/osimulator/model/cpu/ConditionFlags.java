package io.github.rajami1205.osimulator.model.cpu;

/**
 * Estado inmutable de equal y overflow. CMP actualiza igualdad preservando overflow; no reproduce EFLAGS
 * de x86.
 */
public record ConditionFlags(boolean equal, boolean overflow) {
    public static final ConditionFlags CLEAR = new ConditionFlags(false, false);
}
