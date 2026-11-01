package io.github.rajami1205.osimulator.model.memory;

import java.util.Objects;

public record PageTableEntry(int pageNumber, FrameHandle frameHandle) {
    public PageTableEntry {
        if (pageNumber < 0) throw new IllegalArgumentException("Negative page number");
        Objects.requireNonNull(frameHandle);
    }
}
