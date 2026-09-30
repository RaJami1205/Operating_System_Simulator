package io.github.rajami1205.osimulator.model.cpu;

import java.util.Objects;

public record TextRegisterValue(String value) implements RegisterValue {
    public TextRegisterValue { Objects.requireNonNull(value, "value must not be null"); }
}
