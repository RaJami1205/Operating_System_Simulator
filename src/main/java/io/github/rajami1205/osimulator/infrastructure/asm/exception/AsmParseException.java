package io.github.rajami1205.osimulator.infrastructure.asm.exception;

/**
 * Indica que una línea ASM no vacía no puede interpretarse como instrucción.
 */
public final class AsmParseException extends IllegalArgumentException {

    private final int lineNumber;

    // Conserva el diagnóstico y la línea ASM que produjo el fallo.
    public AsmParseException(int lineNumber, String message) {
        super(formatMessage(lineNumber, message));
        this.lineNumber = lineNumber;
    }

    // Conserva el diagnóstico y la causa original del fallo.
    public AsmParseException(int lineNumber, String message, Throwable cause) {
        super(formatMessage(lineNumber, message), cause);
        this.lineNumber = lineNumber;
    }

    // Expone la línea de origen del error de sintaxis.
    public int lineNumber() {
        return lineNumber;
    }

    // Incluye la línea ASM en el diagnóstico de análisis.
    private static String formatMessage(int lineNumber, String message) {
        return "Line " + lineNumber + ": " + message;
    }
}
