package io.github.rajami1205.osimulator.model.memory;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Mapping completo e inmutable; MainMemory valida su vigencia y ownership antes de usarlo. */
public record PageTable(UUID imageId, int processId, int programLength, int pageSize,
        List<PageTableEntry> entries, long revision, PagingLoadMode loadMode) implements ResidentImage {
    public PageTable(UUID imageId, int processId, int programLength, int pageSize, List<PageTableEntry> entries) {
        this(imageId, processId, programLength, pageSize, entries, 0, PagingLoadMode.FULLY_RESIDENT);
    }
    public PageTable {
        Objects.requireNonNull(imageId);
        Objects.requireNonNull(loadMode);
        if (revision < 0) throw new IllegalArgumentException("Negative mapping revision");
        entries = List.copyOf(entries);
        var paging = new PagingConfiguration(pageSize);
        if (processId <= 0 || programLength <= 0
                || (long) io.github.rajami1205.osimulator.model.process.ProcessControlBlock.LOGICAL_CODE_BASE + programLength > Integer.MAX_VALUE
                || entries.size() != paging.requiredFrames(programLength)) {
            throw new IllegalArgumentException("Invalid page table owner or executable length");
        }
        var frames = new HashSet<Integer>();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            if (entry.pageNumber() != i
                    || loadMode == PagingLoadMode.FULLY_RESIDENT && entry.mapping().isEmpty()
                    || entry.mapping().isPresent() && (entry.frameHandle().frame().capacity() != pageSize
                    || !frames.add(entry.frameHandle().frame().id()))) {
                throw new IllegalArgumentException("Page table must map every page to a distinct complete frame");
            }
        }
    }
    @Override public int size() { return programLength; }
    public List<FrameHandle> frames() { return entries.stream().flatMap(entry -> entry.mapping().stream()).toList(); }
    public PageTable updated(List<PageTableEntry> mappings) {
        return new PageTable(imageId, processId, programLength, pageSize, mappings, Math.incrementExact(revision), loadMode);
    }
}
