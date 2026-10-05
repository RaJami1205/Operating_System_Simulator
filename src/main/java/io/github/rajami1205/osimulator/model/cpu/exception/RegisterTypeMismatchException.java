package io.github.rajami1205.osimulator.model.cpu.exception;

/**
 * Señala en la frontera de acceso a registros que se pidió un tipo incompatible, sin convertir texto a
 * número implícitamente.
 */
public final class RegisterTypeMismatchException extends InvalidRegisterValueException {
    /** Conserva el diagnóstico de la condición inválida. */
    public RegisterTypeMismatchException(String message) { super(message); }
}
