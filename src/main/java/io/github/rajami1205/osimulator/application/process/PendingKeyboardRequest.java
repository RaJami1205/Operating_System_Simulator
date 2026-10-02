package io.github.rajami1205.osimulator.application.process;

/** Identifica el PID y PC de un INT09 cuyo peso ya se consumió; no retiene referencias al CPU ni a memoria. */
public record PendingKeyboardRequest(int processId, int interruptPc) {
    /** Exige PID positivo y PC no negativo para identificar la instrucción pendiente. */
    public PendingKeyboardRequest {
        if (processId <= 0 || interruptPc < 0) throw new IllegalArgumentException("Invalid keyboard request");
    }
}
