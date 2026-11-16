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
    private final Map<Integer, PendingPageRequest> pendingPages = new HashMap<>();
    private final java.util.List<PageFaultEvent> pageEvents = new java.util.ArrayList<>();
    public java.util.List<PendingPageRequest> pendingPages() { return pendingPages.values().stream().sorted(java.util.Comparator.comparingInt(PendingPageRequest::processId)).toList(); }
    public java.util.List<PageFaultEvent> pageEvents() { return java.util.List.copyOf(pageEvents); }
    public void recordPageEvent(PageFaultEvent event) { pageEvents.add(Objects.requireNonNull(event)); }
    public void awaitPage(PendingPageRequest request) {
        if (pendingPages.putIfAbsent(request.processId(), request) != null) throw new IllegalStateException("Duplicate pending page request");
    }
    public void clearPageRequest(int pid) { pendingPages.remove(pid); }
    public void clear() { resources.clear(); pendingPages.clear(); pageEvents.clear(); }
    /** Busca recursos canónicos del PID sin asignarlos ni reconstruir handles. */
    public Optional<ProcessResources> find(int pid) { return Optional.ofNullable(resources.get(pid)); }
    /** Devuelve copia inmutable de las asociaciones PID/recursos para comprobaciones de integridad. */
    public Map<Integer, ProcessResources> entries() { return Map.copyOf(resources); }
    /** Publica recursos no nulos para PID positivo y rechaza ownership duplicado. */
    public void register(int pid, ProcessResources value) {
        if (pid <= 0) throw new IllegalArgumentException("PID must be positive");
        Objects.requireNonNull(value);
        validatePagedOwner(pid, value);
        validateUniqueBacking(pid, value);
        if (resources.putIfAbsent(pid, value) != null) throw new IllegalStateException("Resources already registered");
    }
    /** Retira la asociación del PID y devuelve si existía; no libera allocations por sí mismo. */
    public boolean remove(int pid) { pendingPages.remove(pid); return resources.remove(pid) != null; }
    /** Publica recursos de reemplazo sólo si la instancia canónica previa sigue siendo la esperada. */
    void replace(int pid, ProcessResources expected, ProcessResources replacement) {
        Objects.requireNonNull(replacement);
        validatePagedOwner(pid, replacement);
        validateUniqueBacking(pid, replacement);
        if (resources.get(pid) != expected) throw new IllegalStateException("Process resources changed during transfer");
        resources.put(pid, replacement);
    }
    /** Validate every expected identity before publishing the coordinated page-table update. */
    void replaceTogether(Map<Integer, ProcessResources> expected, Map<Integer, ProcessResources> replacements) {
        if (!expected.keySet().equals(replacements.keySet()) || expected.isEmpty()) throw new IllegalArgumentException("Mismatched publication");
        for (var entry : replacements.entrySet()) {
            int pid = entry.getKey();
            if (resources.get(pid) != expected.get(pid)) throw new IllegalStateException("Process resources changed during transfer");
            validatePagedOwner(pid, entry.getValue()); validateUniqueBacking(pid, entry.getValue());
        }
        for (var entry : replacements.entrySet()) resources.put(entry.getKey(), entry.getValue());
    }
    void rollbackTogether(Map<Integer, ProcessResources> previous, Map<Integer, ProcessResources> attempted) {
        RuntimeException failure = null;
        for (int pid : previous.keySet()) try { rollbackReplacement(pid, previous.get(pid), attempted.get(pid)); }
        catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
        if (failure != null) throw failure;
    }
    /** Sólo rollback de una publicación síncrona fallida; nunca reemplaza un tercero. */
    void rollbackReplacement(int pid, ProcessResources previous, ProcessResources attempted) {
        var current = resources.get(pid);
        if (Objects.equals(current, attempted)) resources.put(pid, previous);
        else if (current != previous) throw new IllegalStateException("Resources changed during rollback");
    }

    private static void validatePagedOwner(int pid, ProcessResources resources) {
        var residence = resources.residence();
        if (residence instanceof UserImageResidence.Demand demand && demand.table().processId() != pid
                || residence instanceof UserImageResidence.DemandSuspended suspended && suspended.table().processId() != pid)
            throw new IllegalArgumentException("Demand image owner mismatch");
        if (resources.residence() instanceof UserImageResidence.Paged paged && paged.table().processId() != pid) {
            throw new IllegalArgumentException("Page table owner does not match resource PID");
        }
    }
    private void validateUniqueBacking(int pid, ProcessResources value) {
        var backing = backingOf(value.residence());
        if (backing != null && resources.entrySet().stream().anyMatch(entry -> entry.getKey() != pid
                && backing.equals(backingOf(entry.getValue().residence())))) throw new IllegalArgumentException("Backing allocation already owned");
    }
    private static io.github.rajami1205.osimulator.model.storage.StorageAllocation backingOf(UserImageResidence residence) {
        return switch (residence) {
            case UserImageResidence.Demand demand -> demand.backing().allocation();
            case UserImageResidence.DemandSuspended demand -> demand.backing().allocation();
            case UserImageResidence.Suspended suspended -> suspended.allocation();
            default -> null;
        };
    }
}
