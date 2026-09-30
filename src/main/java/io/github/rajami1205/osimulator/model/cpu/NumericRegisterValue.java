package io.github.rajami1205.osimulator.model.cpu;

public record NumericRegisterValue(int value) implements RegisterValue {
    public NumericRegisterValue { CpuValueRange.validateRegisterValue(value); }
}
