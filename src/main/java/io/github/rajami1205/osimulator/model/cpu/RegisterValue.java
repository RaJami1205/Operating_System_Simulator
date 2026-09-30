package io.github.rajami1205.osimulator.model.cpu;

import io.github.rajami1205.osimulator.model.cpu.exception.RegisterTypeMismatchException;

/** Immutable register data; numeric access never coerces text. */
public sealed interface RegisterValue permits NumericRegisterValue, TextRegisterValue {
    default int numericValue() {
        return switch (this) {
            case NumericRegisterValue numeric -> numeric.value();
            case TextRegisterValue ignored -> throw new RegisterTypeMismatchException("Numeric register value required");
        };
    }
    default String textValue() {
        return switch (this) {
            case TextRegisterValue text -> text.value();
            case NumericRegisterValue ignored -> throw new RegisterTypeMismatchException("Text register value required");
        };
    }
    default String displayText() {
        return switch (this) {
            case NumericRegisterValue numeric -> Integer.toString(numeric.value());
            case TextRegisterValue text -> text.value();
        };
    }
}
