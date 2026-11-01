package io.github.rajami1205.osimulator.model.memory;

/** Geometría relativa a USER; el remanente nunca constituye un frame parcial. */
public record PagingConfiguration(int pageSize) {
    public PagingConfiguration {
        if (pageSize <= 0) throw new IllegalArgumentException("Page size must be positive");
    }
    public static PagingConfiguration defaults() { return new PagingConfiguration(4); }
    public void validate(MemoryConfiguration memory) {
        if (pageSize > memory.userPositions()) throw new IllegalArgumentException("Page size exceeds USER size");
    }
    public int requiredFrames(int programLength) {
        if (programLength <= 0) throw new IllegalArgumentException("Program length must be positive");
        return 1 + (programLength - 1) / pageSize;
    }
}
