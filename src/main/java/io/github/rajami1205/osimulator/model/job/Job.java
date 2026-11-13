package io.github.rajami1205.osimulator.model.job;

import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import java.util.Objects;

/** Referencia lógica al programa almacenado, sin duplicar metadata de storage. */
public record Job(int jobId, String programName, JobState state, long arrivalTime, int priority) {
    public Job(int jobId, String programName, JobState state) {
        this(jobId, programName, state, 0);
    }

    public Job(int jobId, String programName, JobState state, long arrivalTime) {
        this(jobId, programName, state, arrivalTime, ProcessControlBlock.DEFAULT_PRIORITY);
    }
    /** Exige identidad positiva, nombre no vacío y estado no nulo; no crea un proceso. */
    public Job {
        if (arrivalTime < 0) throw new IllegalArgumentException("Arrival Time must be nonnegative");
        if (priority < ProcessControlBlock.MIN_PRIORITY || priority > ProcessControlBlock.MAX_PRIORITY) {
            throw new IllegalArgumentException("Priority must be between " + ProcessControlBlock.MIN_PRIORITY
                    + " and " + ProcessControlBlock.MAX_PRIORITY);
        }
        if (jobId <= 0) throw new IllegalArgumentException("Job ID must be positive");
        Objects.requireNonNull(programName, "programName must not be null");
        if (programName.isBlank()) throw new IllegalArgumentException("Program name must not be blank");
        Objects.requireNonNull(state, "state must not be null");
    }
}
