package io.github.rajami1205.osimulator.application.job;

import io.github.rajami1205.osimulator.application.process.AdmissionResult;
import io.github.rajami1205.osimulator.application.process.ProcessAdmissionService;
import io.github.rajami1205.osimulator.model.job.JobList;
import io.github.rajami1205.osimulator.model.job.JobState;
import java.util.Objects;
import java.util.Optional;

/** Selección de admisión por submission order, independiente del futuro CPU scheduler. */
public final class JobScheduler {
    private final JobList jobs;
    private final ProcessAdmissionService admission;

    public JobScheduler(JobList jobs, ProcessAdmissionService admission) {
        this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
        this.admission = Objects.requireNonNull(admission, "admission must not be null");
    }

    /** Intenta como máximo el primer PENDING; no salta un Job que deba esperar. */
    public Optional<AdmissionResult> attemptNextAdmission() {
        return jobs.entries().stream().filter(job -> job.state() == JobState.PENDING)
                .findFirst().map(job -> admission.admit(job.jobId()));
    }
}
