package io.github.rajami1205.osimulator.model.job;

import java.util.Objects;

/** Referencia lógica al programa almacenado, sin duplicar metadata de storage. */
public record Job(int jobId, String programName, JobState state) {
    public Job {
        if (jobId <= 0) throw new IllegalArgumentException("Job ID must be positive");
        Objects.requireNonNull(programName, "programName must not be null");
        if (programName.isBlank()) throw new IllegalArgumentException("Program name must not be blank");
        Objects.requireNonNull(state, "state must not be null");
    }
}
