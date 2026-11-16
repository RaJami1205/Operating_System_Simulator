package io.github.rajami1205.osimulator.application.job;

import io.github.rajami1205.osimulator.application.process.AdmissionResult;
import io.github.rajami1205.osimulator.application.process.ProcessAdmissionService;
import io.github.rajami1205.osimulator.model.job.JobList;
import io.github.rajami1205.osimulator.model.job.JobState;
import io.github.rajami1205.osimulator.model.job.Job;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

/** Selecciona por Arrival e identidad; no elige al owner ni adelanta Jobs por su tamaño. */
public final class JobScheduler {
    private final JobList jobs;
    private final ProcessAdmissionService admission;

    /** Recibe JobList y servicio de admission no nulos para coordinar un intento en orden de llegada. */
    public JobScheduler(JobList jobs, ProcessAdmissionService admission) {
        this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
        this.admission = Objects.requireNonNull(admission, "admission must not be null");
    }

    /** Intenta como máximo el primer PENDING; no salta un Job que deba esperar. */
    public Optional<AdmissionResult> attemptNextAdmission() {
        return attemptNextAdmission(0);
    }

    /** El primer elegible bloquea a los demás si faltan recursos; los futuros no participan. */
    public Optional<AdmissionResult> attemptNextAdmission(long currentTime) {
        if (currentTime < 0) throw new IllegalArgumentException("Negative admission time");
        return jobs.entries().stream().filter(job -> job.state() == JobState.PENDING)
                .filter(job -> job.arrivalTime() <= currentTime)
                .min(Comparator.comparingLong(Job::arrivalTime).thenComparingInt(Job::jobId))
                .map(job -> admission.admit(job.jobId(), currentTime));
    }
}
