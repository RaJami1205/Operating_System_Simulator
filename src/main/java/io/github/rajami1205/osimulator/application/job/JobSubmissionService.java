package io.github.rajami1205.osimulator.application.job;

import io.github.rajami1205.osimulator.model.job.Job;
import io.github.rajami1205.osimulator.model.job.JobList;
import io.github.rajami1205.osimulator.model.job.JobState;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import java.util.Objects;

/**
 * Coordina una submission sin crear procesos.
 * La futura gestión debe impedir eliminar/reemplazar programas referenciados por Jobs.
 */
public final class JobSubmissionService {
    private final SecondaryStorage storage;
    private final JobList jobs;
    // long permite representar el agotamiento después de publicar Integer.MAX_VALUE.
    private long nextJobId = 1;

    public JobSubmissionService(SecondaryStorage storage, JobList jobs) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
    }

    public Job submit(ProgramImage program) {
        Objects.requireNonNull(program, "program must not be null");
        if (nextJobId > Integer.MAX_VALUE) throw new IllegalStateException("Job IDs exhausted");
        var job = new Job((int) nextJobId, program.logicalName(), JobState.PENDING);
        if (jobs.find(job.jobId()).isPresent()) throw new IllegalStateException("Next Job ID is already in use");
        // Un fallo aquí no autoriza eliminar un programa que ya existía.
        storage.storeProgram(program.logicalName(), program.instructions());
        try {
            jobs.add(job);
        } catch (RuntimeException | Error failure) {
            try {
                storage.removeProgram(program.logicalName());
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
        nextJobId++;
        return job;
    }
}
