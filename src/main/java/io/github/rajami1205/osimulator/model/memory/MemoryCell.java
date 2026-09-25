package io.github.rajami1205.osimulator.model.memory;

import java.util.Objects;

/** Vista inmutable de una celda; PcbContent conserva el PCB canónico mutable. */
public record MemoryCell(int address, MemoryRegion region, MemoryContent content) {
    public MemoryCell {
        if (address < 0) throw new IllegalArgumentException("Cell address must not be negative");
        Objects.requireNonNull(region, "region must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
