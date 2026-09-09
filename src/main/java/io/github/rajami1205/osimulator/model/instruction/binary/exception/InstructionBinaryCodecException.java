package io.github.rajami1205.osimulator.model.instruction.binary.exception;

/**
 * Indicates that data does not satisfy the strict Binary Format v1 contract.
 */
public final class InstructionBinaryCodecException extends RuntimeException {

    public InstructionBinaryCodecException(String message) {
        super(message);
    }

    public InstructionBinaryCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
