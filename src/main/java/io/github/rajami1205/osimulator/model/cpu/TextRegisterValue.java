package io.github.rajami1205.osimulator.model.cpu;

import java.util.Objects;

/** Texto inmutable no nulo para registros que admiten contenido textual, sin interpretación numérica. */
public record TextRegisterValue(String value) implements RegisterValue {
    /** Exige contenido textual no nulo; permite texto vacío. */
    public TextRegisterValue { Objects.requireNonNull(value, "value must not be null"); }
}
