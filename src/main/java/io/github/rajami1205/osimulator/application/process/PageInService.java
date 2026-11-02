package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryAllocationException;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import java.util.Objects;

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
        validatePending(pcb, request);
        var original = resources.find(pcb.processId()).orElseThrow();
        var demand = (UserImageResidence.Demand) original.residence();
        var backing = demand.backing();
        // Corrupción del respaldo nunca equivale a Waiting, incluso con el pool lleno.
        storage.readSwapSegment(backing.allocation(), backing.offset(request.pageNumber()), backing.length(request.pageNumber()));
        MainMemory.PageReservation token;
        try { token = memory.reservePage(demand.table(), request.pageNumber()); }
        catch (MemoryAllocationException shortage) { return false; }
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
}
