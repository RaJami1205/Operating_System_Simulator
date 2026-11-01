package io.github.rajami1205.osimulator.model.memory;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Mapping completo e inmutable; MainMemory valida su vigencia y ownership antes de usarlo. */
public record PageTable(UUID imageId, int processId, int programLength, int pageSize,
        List<PageTableEntry> entries) implements ResidentImage {
    public PageTable {
        Objects.requireNonNull(imageId);
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
            if (entry.pageNumber() != i || entry.frameHandle().frame().capacity() != pageSize
                    || !frames.add(entry.frameHandle().frame().id())) {
                throw new IllegalArgumentException("Page table must map every page to a distinct complete frame");
            }
        }
    }
    @Override public int size() { return programLength; }
    public List<FrameHandle> frames() { return entries.stream().map(PageTableEntry::frameHandle).toList(); }
}
