package io.github.rajami1205.osimulator.model.cpu;

import io.github.rajami1205.osimulator.model.cpu.exception.RegisterTypeMismatchException;

/** Contenido inmutable numérico o textual; los accesos incompatibles fallan explícitamente sin coerción. */
public sealed interface RegisterValue permits NumericRegisterValue, TextRegisterValue {
    /** Devuelve el número almacenado o lanza RegisterTypeMismatchException si el valor es texto. */
    default int numericValue() {
        return switch (this) {
            case NumericRegisterValue numeric -> numeric.value();
            case TextRegisterValue ignored -> throw new RegisterTypeMismatchException("Numeric register value required");
        };
    }
    /** Devuelve el texto almacenado o lanza RegisterTypeMismatchException si el valor es numérico. */
    default String textValue() {
        return switch (this) {
            case TextRegisterValue text -> text.value();
            case NumericRegisterValue ignored -> throw new RegisterTypeMismatchException("Text register value required");
        };
    }
    /** Produce texto para visualización sin cambiar el tipo canónico del registro. */
    default String displayText() {
        return switch (this) {
            case NumericRegisterValue numeric -> Integer.toString(numeric.value());
            case TextRegisterValue text -> text.value();
        };
    }
}
