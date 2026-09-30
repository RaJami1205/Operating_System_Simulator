package io.github.rajami1205.osimulator.model.cpu.exception;

/** A typed register cannot be used as the requested data kind. */
public final class RegisterTypeMismatchException extends InvalidRegisterValueException {
    public RegisterTypeMismatchException(String message) { super(message); }
}
