package io.github.rajami1205.osimulator.model.instruction.binary.exception;

/**
 * Indica que los datos incumplen el contrato estricto de Binary Format v1.
 */
public final class InstructionBinaryCodecException extends RuntimeException {

    // Conserva el diagnóstico de la condición inválida.
    public InstructionBinaryCodecException(String message) {
        super(message);
    }

    // Conserva el diagnóstico y la causa original del fallo.
    public InstructionBinaryCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
