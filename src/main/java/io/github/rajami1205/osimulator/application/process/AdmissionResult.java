package io.github.rajami1205.osimulator.application.process;

import java.util.Objects;

/** Resultado de admission sin exponer entidades ni handles mutables a Presentation. */
public sealed interface AdmissionResult {
    /** Admisión publicada con el PID creado; no expone el PCB a Presentation. */
    record Admitted(int processId) implements AdmissionResult {
        /** Exige PID positivo antes de publicar el resultado de admission. */
        public Admitted {
            if (processId <= 0) throw new IllegalArgumentException("Process ID must be positive");
        }
    }

    /** Job que continúa pendiente por una causa de capacidad explícita. */
    record Waiting(Reason reason) implements AdmissionResult {
        /** Exige una causa no nula para distinguir espera ordinaria de fallo de integridad. */
        public Waiting { Objects.requireNonNull(reason, "reason must not be null"); }
    }

    /** Recurso que impide admitir: límite de procesos, USER o Kernel insuficiente. */
    enum Reason {
        RESIDENT_CAPACITY_REACHED,
        INSUFFICIENT_USER_MEMORY,
        INSUFFICIENT_KERNEL_MEMORY
    }
}
