package io.github.rajami1205.osimulator.application.statistics;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Registro mutable del Gantt de la sesión, propiedad del runtime. Recibe un slot por CPU cada vez que el
 * reloj global avanza un segundo; no decide planificación ni consulta el reloj.
 */
public final class ExecutionTimeline {
    private final List<CpuId> cpus;
    private final List<List<Optional<Integer>>> slotsByCpu = new ArrayList<>();

    public ExecutionTimeline(List<CpuId> cpus) {
        this.cpus = List.copyOf(cpus);
        if (this.cpus.isEmpty()) {
            throw new IllegalArgumentException("At least one CPU is required");
        }
        for (int i = 0; i < this.cpus.size(); i++) {
            slotsByCpu.add(new ArrayList<>());
        }
    }

    /** Agrega el tick recién completado: un valor por CPU, en el orden de las CPUs. */
    public void record(List<Optional<Integer>> tick) {
        Objects.requireNonNull(tick, "tick must not be null");
        if (tick.size() != cpus.size()) {
            throw new IllegalArgumentException("Timeline tick must have one slot per CPU");
        }
        for (int i = 0; i < cpus.size(); i++) {
            Optional<Integer> slot = Objects.requireNonNull(tick.get(i), "slot must not be null");
            slotsByCpu.get(i).add(slot);
        }
    }

    /** Copia inmutable del Gantt de cada CPU. */
    public List<CpuTimelineView> views() {
        List<CpuTimelineView> views = new ArrayList<>();
        for (int i = 0; i < cpus.size(); i++) {
            views.add(new CpuTimelineView(cpus.get(i), slotsByCpu.get(i)));
        }
        return List.copyOf(views);
    }
}
