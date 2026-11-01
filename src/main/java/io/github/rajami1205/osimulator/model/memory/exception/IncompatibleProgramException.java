package io.github.rajami1205.osimulator.model.memory.exception;

/** Incompatibilidad permanente con el layout, distinta de escasez temporal de allocations. */
public final class IncompatibleProgramException extends IllegalArgumentException {
    public IncompatibleProgramException(int length, int maximum) {
        super("Program length " + length + " exceeds maximum partition capacity " + maximum);
    }
}
