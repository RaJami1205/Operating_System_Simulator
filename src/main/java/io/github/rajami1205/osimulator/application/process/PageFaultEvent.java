package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import java.util.Objects;

/** Eventos observados; no representan ticks de servicio. */
public record PageFaultEvent(long time, CpuId cpuId, int processId, int pageNumber, Kind kind) {
    public enum Kind { FAULT_OBSERVED, PAGE_IN_COMMITTED }
    public PageFaultEvent {
        Objects.requireNonNull(cpuId); Objects.requireNonNull(kind);
        if (time < 0 || processId <= 0 || pageNumber < 0) throw new IllegalArgumentException("Invalid page event");
    }
}
