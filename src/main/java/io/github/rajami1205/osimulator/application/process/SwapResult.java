package io.github.rajami1205.osimulator.application.process;

import java.util.Objects;

/**
 * Distingue una transferencia completada de una espera por capacidad; los fallos de integridad se propagan
 * como excepciones.
 */
public sealed interface SwapResult {
    /** Confirma una transferencia ya publicada y la liberación de la residencia anterior. */
    record Completed() implements SwapResult {}
    /** Transferencia no realizada por falta de capacidad; conserva la residencia previa. */
    record Waiting(Reason reason) implements SwapResult {
        /** Exige una causa no nula para distinguir espera ordinaria de fallo de integridad. */
        public Waiting { Objects.requireNonNull(reason); }
    }
    /** Distingue falta de SWAP al salir y falta de USER al volver. */
    enum Reason { INSUFFICIENT_SWAP, INSUFFICIENT_USER_MEMORY }
}
