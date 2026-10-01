package io.github.rajami1205.osimulator.application.process;

/** INT09 CPU cost has already completed; no active CPU or memory reference is retained. */
public record PendingKeyboardRequest(int processId, int interruptPc) {
    public PendingKeyboardRequest {
        if (processId <= 0 || interruptPc < 0) throw new IllegalArgumentException("Invalid keyboard request");
    }
}
