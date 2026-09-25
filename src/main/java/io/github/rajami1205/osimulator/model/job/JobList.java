package io.github.rajami1205.osimulator.model.job;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Registro en orden de submission, sin política de selección ni límite de procesos. */
public final class JobList {
    private Map<Integer, Job> jobs = new LinkedHashMap<>();

    /** Valida y prepara la publicación completa antes de sustituir el estado visible. */
    public void add(Job job) {
        Objects.requireNonNull(job, "job must not be null");
        if (jobs.containsKey(job.jobId())) throw new IllegalArgumentException("Duplicate Job ID: " + job.jobId());
        var updated = new LinkedHashMap<>(jobs);
        updated.put(job.jobId(), job);
        jobs = updated;
    }

    public Optional<Job> find(int jobId) {
        validateId(jobId);
        return Optional.ofNullable(jobs.get(jobId));
    }

    public List<Job> entries() { return List.copyOf(jobs.values()); }

    /** Publica la transición completa sin alterar el orden ni las vistas anteriores. */
    public void markAdmitted(int jobId) {
        Job job = find(jobId).orElseThrow(() -> new IllegalArgumentException("Unknown Job ID: " + jobId));
        if (job.state() != JobState.PENDING) throw new IllegalStateException("Job is already admitted: " + jobId);
        var updated = new LinkedHashMap<>(jobs);
        updated.put(jobId, new Job(jobId, job.programName(), JobState.ADMITTED));
        jobs = updated;
    }

    public boolean remove(int jobId) {
        validateId(jobId);
        return jobs.remove(jobId) != null;
    }

    private static void validateId(int jobId) {
        if (jobId <= 0) throw new IllegalArgumentException("Job ID must be positive");
    }
}
