package io.github.rajami1205.filesystem.model.disk;

import java.util.List;

public record IndexBlock(int next, List<Integer> references) {
    public IndexBlock {
        references = List.copyOf(references);
    }

    public static int capacity(DiskGeometry g) { return (g.blockSize() - 16) / 4; }

    public void validate(DiskGeometry g) {
        int count = references.size();
        if (count < 1 || count > capacity(g) || next != -1 && count != capacity(g))
            throw new IllegalArgumentException("Invalid index reference count");
        if (next != -1) validateId(g, next);
        for (int id : references) validateId(g, id);
    }

    private static void validateId(DiskGeometry g, int id) {
        if (id < g.firstAllocatable() || id >= g.totalBlocks())
            throw new IllegalArgumentException("Invalid dynamic block ID: " + id);
    }
}
