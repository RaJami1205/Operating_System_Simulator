package io.github.rajami1205.osimulator.model.storage;

import io.github.rajami1205.osimulator.model.memory.PageTable;
import io.github.rajami1205.osimulator.model.memory.PagingLoadMode;
import java.util.Objects;
import java.util.UUID;

/** Una sola reserva física de respaldo por imagen; segmentos derivados, nunca reservas por página. */
public record PageBacking(UUID imageId, int processId, int programLength, int pageSize, StorageAllocation allocation) {
    public PageBacking {
        Objects.requireNonNull(imageId); Objects.requireNonNull(allocation);
        if (processId <= 0 || programLength <= 0 || pageSize <= 0 || allocation.size() != programLength)
            throw new IllegalArgumentException("Invalid backing descriptor");
    }
    public void validate(PageTable table) {
        if (!imageId.equals(table.imageId()) || processId != table.processId() || programLength != table.size()
                || pageSize != table.pageSize() || table.loadMode() != PagingLoadMode.DEMAND)
            throw new IllegalArgumentException("Backing owner/image mismatch");
    }
    public int offset(int page) {
        long offset = (long) page * pageSize;
        if (page < 0 || offset >= programLength) throw new IllegalArgumentException("Page outside backing image");
        return (int) offset;
    }
    public int length(int page) { return Math.min(pageSize, programLength - offset(page)); }
}
