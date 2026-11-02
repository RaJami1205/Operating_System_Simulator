package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryAllocationException;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import java.util.*;

/** Coordina una única transacción de Page-In; no ejecuta ni decide scheduling. */
public final class PageInService {
    private final MainMemory memory;
    private final SecondaryStorage storage;
    private final ProcessResourceRegistry resources;
    public PageInService(MainMemory memory, SecondaryStorage storage, ProcessResourceRegistry resources) {
        this.memory = Objects.requireNonNull(memory); this.storage = Objects.requireNonNull(storage);
        this.resources = Objects.requireNonNull(resources);
    }
    public PendingPageRequest request(ProcessControlBlock pcb) {
        var original = resources.find(pcb.processId()).orElseThrow();
        if (!(original.residence() instanceof UserImageResidence.Demand demand)) throw new IllegalStateException("Active demand image required");
        memory.validateImage(demand.table(), pcb); demand.backing().validate(demand.table());
        var resolution = memory.resolveInstructionAddress(demand.table(), pcb.logicalCodeRange(), new LogicalAddress(pcb.programCounter()));
        if (!(resolution instanceof AddressResolution.PageNotPresent absent)) throw new IllegalStateException("Page is already present");
        return new PendingPageRequest(pcb.processId(), absent.imageId(), absent.pageNumber(), pcb.programCounter());
    }
    public void validateBacking(ProcessControlBlock pcb) {
        var resource = resources.find(pcb.processId()).orElseThrow();
        if (resource.residence() instanceof UserImageResidence.Demand demand) {
            demand.backing().validate(demand.table()); storage.validateBacking(demand.backing());
        }
    }
    public void validatePending(ProcessControlBlock pcb, PendingPageRequest pending) {
        if (!request(pcb).equals(pending)) throw new IllegalStateException("Stale/foreign pending page request");
    }
    public boolean resolve(ProcessControlBlock pcb, PendingPageRequest request) {
        return resolve(pcb, request, 0);
    }
    public boolean resolve(ProcessControlBlock pcb, PendingPageRequest request, long time) {
        if (time < 0) throw new IllegalArgumentException("Negative event time");
        validatePending(pcb, request);
        var original = resources.find(pcb.processId()).orElseThrow();
        var demand = (UserImageResidence.Demand) original.residence();
        var backing = demand.backing();
        // Corrupción del respaldo nunca equivale a Waiting, incluso con el pool lleno.
        storage.readSwapSegment(backing.allocation(), backing.offset(request.pageNumber()), backing.length(request.pageNumber()));
        if (!memory.pageOperationAvailable()) return false;
        MainMemory.PageReservation token;
        try { token = memory.reservePage(demand.table(), request.pageNumber()); }
        catch (MemoryAllocationException shortage) { return replace(pcb, request, original, demand, time); }
        try {
            var page = storage.readSwapSegment(backing.allocation(), backing.offset(request.pageNumber()), backing.length(request.pageNumber()));
            memory.writePage(token, page);
            var replacement = original.withResidence(new UserImageResidence.Demand(token.replacement(), backing));
            memory.commitPage(token, () -> resources.replace(pcb.processId(), original, replacement),
                    () -> resources.rollbackReplacement(pcb.processId(), original, replacement));
            return true;
        } catch (RuntimeException | Error failure) {
            try { memory.cancelPage(token); } catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private record Candidate(ProcessResources resources, UserImageResidence.Demand demand, int page, FrameHandle handle) { }

    /** Integrity checks are shared by pure availability queries and actual selection. */
    private List<Candidate> candidates() {
        var result = new ArrayList<Candidate>();
        for (var table : memory.demandReplacementImages()) {
            var owner = resources.find(table.processId()).orElseThrow(() -> new MemoryProtectionException("Unregistered page owner"));
            if (owner.residence() instanceof UserImageResidence.DemandSuspended suspended) {
                if (!suspended.table().equals(table) || !table.frames().isEmpty()) throw new MemoryProtectionException("Invalid suspended mapping");
                continue;
            }
            if (!(owner.residence() instanceof UserImageResidence.Demand demand) || !demand.table().equals(table))
                throw new MemoryProtectionException("Registry and memory page tables disagree");
            demand.backing().validate(table); storage.validateBacking(demand.backing());
            for (var entry : table.entries()) if (entry.mapping().isPresent()) {
                var handle = entry.frameHandle();
                var backing = demand.backing();
                var saved = storage.readSwapSegment(backing.allocation(), backing.offset(entry.pageNumber()), backing.length(entry.pageNumber()));
                for (int i = 0; i < saved.size(); i++) {
                    if (!saved.get(i).equals(memory.readInstruction(table, handle.frame().base() + i)))
                        throw new MemoryProtectionException("Resident page differs from its backing store");
                }
                if (!memory.isReplacementProtected(table.processId())) result.add(new Candidate(owner, demand, entry.pageNumber(), handle));
            }
        }
        return List.copyOf(result);
    }
    public boolean canResolve() {
        return memory.pageOperationAvailable() && (memory.hasFreeFrame() || !candidates().isEmpty());
    }
    private boolean replace(ProcessControlBlock pcb, PendingPageRequest request, ProcessResources original,
            UserImageResidence.Demand demand, long time) {
        var candidates = candidates();
        var selection = memory.selectVictim(candidates.stream().map(Candidate::handle).toList());
        if (selection.isEmpty()) return false;
        var plan = selection.orElseThrow();
        var victim = candidates.stream().filter(c -> c.handle.equals(plan.victim())).findFirst().orElseThrow();
        var backing = demand.backing();
        var page = storage.readSwapSegment(backing.allocation(), backing.offset(request.pageNumber()), backing.length(request.pageNumber()));
        var token = memory.reserveReplacement(demand.table(), request.pageNumber(), victim.demand.table(), victim.page, plan);
        try {
            var previous = new HashMap<Integer, ProcessResources>();
            previous.put(pcb.processId(), original); previous.put(victim.demand.table().processId(), victim.resources);
            var replacements = new HashMap<Integer, ProcessResources>();
            for (var entry : token.replacements().entrySet()) {
                var before = previous.get(entry.getKey());
                var oldDemand = (UserImageResidence.Demand) before.residence();
                replacements.put(entry.getKey(), before.withResidence(new UserImageResidence.Demand(entry.getValue(), oldDemand.backing())));
            }
            memory.writePage(token, page);
            memory.commitPage(token, () -> resources.replaceTogether(previous, replacements),
                    () -> resources.rollbackTogether(previous, replacements));
        } catch (RuntimeException | Error failure) {
            try { memory.cancelPage(token); } catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
        resources.recordPageEvent(new PageFaultEvent(time, pcb.cpuAffinity(), pcb.processId(), request.pageNumber(),
                PageFaultEvent.Kind.EVICTION_COMMITTED, Optional.of(new PageFaultEvent.Eviction(
                        victim.demand.table().processId(), victim.page, victim.handle.frame().id()))));
        return true;
    }
}
