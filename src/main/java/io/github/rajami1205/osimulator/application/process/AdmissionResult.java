package io.github.rajami1205.osimulator.application.process;

import java.util.Objects;

/** Resultado de admission sin exponer entidades ni handles mutables a Presentation. */
public sealed interface AdmissionResult {
    record Admitted(int processId) implements AdmissionResult {
        public Admitted {
            if (processId <= 0) throw new IllegalArgumentException("Process ID must be positive");
        }
    }

    record Waiting(Reason reason) implements AdmissionResult {
        public Waiting { Objects.requireNonNull(reason, "reason must not be null"); }
    }

    enum Reason {
        RESIDENT_CAPACITY_REACHED,
        INSUFFICIENT_USER_MEMORY,
        INSUFFICIENT_KERNEL_MEMORY
    }
}
