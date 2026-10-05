package io.github.rajami1205.osimulator.application.process;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registro canónico de recursos por PID, compartido por admission, Dispatcher, swap y completion durante
 * la sesión.
 */
public final class ProcessResourceRegistry {
    private final Map<Integer, ProcessResources> resources = new HashMap<>();
    /** Busca recursos canónicos del PID sin asignarlos ni reconstruir handles. */
    public Optional<ProcessResources> find(int pid) { return Optional.ofNullable(resources.get(pid)); }
    /** Devuelve copia inmutable de las asociaciones PID/recursos para comprobaciones de integridad. */
    public Map<Integer, ProcessResources> entries() { return Map.copyOf(resources); }
    /** Publica recursos no nulos para PID positivo y rechaza ownership duplicado. */
    public void register(int pid, ProcessResources value) {
        if (pid <= 0) throw new IllegalArgumentException("PID must be positive");
        Objects.requireNonNull(value);
        if (resources.putIfAbsent(pid, value) != null) throw new IllegalStateException("Resources already registered");
    }
    /** Retira la asociación del PID y devuelve si existía; no libera allocations por sí mismo. */
    public boolean remove(int pid) { return resources.remove(pid) != null; }
    /** Publica recursos de reemplazo sólo si la instancia canónica previa sigue siendo la esperada. */
    void replace(int pid, ProcessResources expected, ProcessResources replacement) {
        Objects.requireNonNull(replacement);
        if (resources.get(pid) != expected) throw new IllegalStateException("Process resources changed during transfer");
        resources.put(pid, replacement);
    }
}
