package io.github.rajami1205.filesystem.model.disk;

/** Active record. A free slot has no FCB. */
public record Fcb(Type type, int mode, int id, int owner, int group, int parent, int firstIndex,
                  long size, long created, long modified) {
    public enum Type { FILE, DIRECTORY, SYMBOLIC_LINK }

    public Fcb {
        if (type == null || mode < 0 || mode > 63 || id < 0 || owner < 0 || owner >= DiskGeometry.USER_COUNT
                || group < 0 || group >= DiskGeometry.GROUP_COUNT || parent < 0 || size < 0
                || firstIndex < -1 || (size == 0) != (firstIndex == -1))
            throw new IllegalArgumentException("Invalid active FCB");
        if (id == 0 && (parent != 0 || type != Type.DIRECTORY || owner != 0))
            throw new IllegalArgumentException("Invalid root FCB");
    }

    public void validate(DiskGeometry g, int slot) {
        if (id != slot || id >= g.fcbCapacity() || parent >= g.fcbCapacity()
                || firstIndex != -1 && (firstIndex < g.firstAllocatable() || firstIndex >= g.totalBlocks()))
            throw new IllegalArgumentException("FCB outside geometry or wrong slot");
        long data = Math.ceilDiv(size, g.blockSize());
        long indices = Math.ceilDiv(data, IndexBlock.capacity(g));
        if (data + indices > g.allocatableBlocks()) throw new IllegalArgumentException("Impossible content size");
    }

    public Fcb replace(int first, long length, long time) {
        return new Fcb(type, mode, id, owner, group, parent, first, length, created, time);
    }
}
