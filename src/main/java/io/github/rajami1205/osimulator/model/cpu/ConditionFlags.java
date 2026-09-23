package io.github.rajami1205.osimulator.model.cpu;

/** Estado inmutable mínimo de condición; las instrucciones actuales no lo modifican. */
public record ConditionFlags(boolean equal, boolean overflow) {
    public static final ConditionFlags CLEAR = new ConditionFlags(false, false);
}
