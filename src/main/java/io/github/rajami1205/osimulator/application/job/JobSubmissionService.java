package io.github.rajami1205.osimulator.application.job;

import io.github.rajami1205.osimulator.model.job.Job;
import io.github.rajami1205.osimulator.model.job.JobList;
import io.github.rajami1205.osimulator.model.job.JobState;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import java.util.Objects;

/**
 * Coordina una submission sin crear procesos.
 * La futura gestión debe impedir eliminar/reemplazar programas referenciados por Jobs.
 */
public final class JobSubmissionService {
    private final SecondaryStorage storage;
    private final JobList jobs;
    private final java.util.function.IntConsumer validateProgramLength;
    // long permite representar el agotamiento después de publicar Integer.MAX_VALUE.
    private long nextJobId = 1;

    /** Asocia storage y JobList de la sesión e inicia la secuencia de identificadores en uno. */
    public JobSubmissionService(SecondaryStorage storage, JobList jobs) {
        this(storage, jobs, length -> {}); // API histórica sin restricciones de partición fija.
    }

    public JobSubmissionService(SecondaryStorage storage, JobList jobs, MainMemory memory) {
        this(storage, jobs, length -> {
            Objects.requireNonNull(memory).validateProgramLength(length);
            if (memory.isDemandPaging() && length > storage.virtualMemoryPositions())
                throw new io.github.rajami1205.osimulator.model.memory.exception.IncompatibleProgramException("Program exceeds backing capacity");
        });
    }

    private JobSubmissionService(SecondaryStorage storage, JobList jobs, java.util.function.IntConsumer validation) {
        this.storage = Objects.requireNonNull(storage, "storage must not be null");
        this.jobs = Objects.requireNonNull(jobs, "jobs must not be null");
        this.validateProgramLength = validation;
    }

    /**
     * Almacena el programa y publica un Job PENDING; si falla JobList revierte sólo el programa recién
     * creado y conserva el diagnóstico de rollback.
     */
    public Job submit(ProgramImage program) {
        return submit(program, 0);
    }

    /** Valida Arrival antes de publicar el programa o reservar almacenamiento. */
    public Job submit(ProgramImage program, long arrivalTime) {
        return submit(program, arrivalTime, ProcessControlBlock.DEFAULT_PRIORITY);
    }

    /** Valida Arrival y prioridad (en el Job) antes de publicar el programa o reservar almacenamiento. */
    public Job submit(ProgramImage program, long arrivalTime, int priority) {
        Objects.requireNonNull(program, "program must not be null");
        validateProgramLength.accept(program.instructions().size());
        if (nextJobId > Integer.MAX_VALUE) throw new IllegalStateException("Job IDs exhausted");
        var job = new Job((int) nextJobId, program.logicalName(), JobState.PENDING, arrivalTime, priority);
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
