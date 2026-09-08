package io.github.rajami1205.osimulator.infrastructure.asm.exception;

/**
 * Indicates that a non-blank ASM source line cannot be parsed as an instruction.
 */
public final class AsmParseException extends IllegalArgumentException {

    private final int lineNumber;

    public AsmParseException(int lineNumber, String message) {
        super(formatMessage(lineNumber, message));
        this.lineNumber = lineNumber;
    }

    public AsmParseException(int lineNumber, String message, Throwable cause) {
        super(formatMessage(lineNumber, message), cause);
        this.lineNumber = lineNumber;
    }

    public int lineNumber() {
        return lineNumber;
    }

    private static String formatMessage(int lineNumber, String message) {
        return "Line " + lineNumber + ": " + message;
    }
}
