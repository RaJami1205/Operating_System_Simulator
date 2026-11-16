package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import java.util.Objects;

/** Eventos observados; no representan ticks de servicio. */
public record PageFaultEvent(long time, CpuId cpuId, int processId, int pageNumber, Kind kind,
        java.util.Optional<Eviction> eviction) {
    public enum Kind { FAULT_OBSERVED, PAGE_IN_COMMITTED, EVICTION_COMMITTED }
    public record Eviction(int victimProcessId, int victimPageNumber, int frameId) {
        public Eviction {
            if (victimProcessId <= 0 || victimPageNumber < 0 || frameId < 0) throw new IllegalArgumentException("Invalid eviction");
        }
    }
    public PageFaultEvent(long time, CpuId cpuId, int processId, int pageNumber, Kind kind) {
        this(time, cpuId, processId, pageNumber, kind, java.util.Optional.empty());
    }
    public PageFaultEvent {
        Objects.requireNonNull(cpuId); Objects.requireNonNull(kind);
        Objects.requireNonNull(eviction);
        if ((kind == Kind.EVICTION_COMMITTED) != eviction.isPresent()) throw new IllegalArgumentException("Eviction details required only for committed eviction");
        if (time < 0 || processId <= 0 || pageNumber < 0) throw new IllegalArgumentException("Invalid page event");
    }
}
