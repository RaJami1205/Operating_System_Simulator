package io.github.rajami1205.osimulator.model.memory;

import java.util.Objects;

public record PageTableEntry(int pageNumber, java.util.Optional<FrameHandle> mapping) {
    public PageTableEntry(int pageNumber, FrameHandle frameHandle) {
        this(pageNumber, java.util.Optional.of(frameHandle));
    }
    /** Acceso histórico estricto para páginas presentes. */
    public FrameHandle frameHandle() { return mapping.orElseThrow(() -> new IllegalStateException("Page not present")); }
    public PageTableEntry {
        if (pageNumber < 0) throw new IllegalArgumentException("Negative page number");
        Objects.requireNonNull(mapping);
    }
}
