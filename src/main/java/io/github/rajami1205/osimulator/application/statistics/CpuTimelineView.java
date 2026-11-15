package io.github.rajami1205.osimulator.application.statistics;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Gantt inmutable de una CPU: un slot por segundo lógico desde t = 0. Cada slot es el PID que completó
 * una instrucción en ese tick, o vacío si la CPU estuvo ociosa.
 */
public record CpuTimelineView(CpuId cpu, List<Optional<Integer>> slots) {
    public CpuTimelineView {
        Objects.requireNonNull(cpu, "cpu must not be null");
        slots = List.copyOf(slots);
    }

    /** Ticks en que la CPU ejecutó una instrucción. */
    public long busyTicks() {
        return slots.stream().filter(Optional::isPresent).count();
    }
}
