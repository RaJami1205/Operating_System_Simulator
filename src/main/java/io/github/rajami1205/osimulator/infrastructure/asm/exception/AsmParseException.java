package io.github.rajami1205.osimulator.infrastructure.asm.exception;

/**
 * Indica que una línea ASM no vacía no puede interpretarse como instrucción.
 */
public final class AsmParseException extends IllegalArgumentException {

    private final int lineNumber;

    /** Conserva la línea ASM y el diagnóstico; el overload con causa mantiene también el fallo original. */
    public AsmParseException(int lineNumber, String message) {
        super(formatMessage(lineNumber, message));
        this.lineNumber = lineNumber;
    }

    /** Conserva la línea ASM y el diagnóstico; el overload con causa mantiene también el fallo original. */
    public AsmParseException(int lineNumber, String message, Throwable cause) {
        super(formatMessage(lineNumber, message), cause);
        this.lineNumber = lineNumber;
    }

    /** Expone la línea de origen del error de sintaxis. */
    public int lineNumber() {
        return lineNumber;
    }

    /** Incluye la línea ASM en el diagnóstico de análisis. */
    private static String formatMessage(int lineNumber, String message) {
        return "Line " + lineNumber + ": " + message;
    }
}
