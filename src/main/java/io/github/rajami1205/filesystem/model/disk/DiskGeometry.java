package io.github.rajami1205.filesystem.model.disk;

/** All region starts and lengths use global blocks, except explicitly named sector/byte counts. */
public record DiskGeometry(long diskBytes, int blockSize) {
    public static final int SECTOR_BYTES = 512;
    public static final int BITMAP_START = 2;
    public static final int USER_COUNT = 128, USER_BYTES = 256;
    public static final int GROUP_COUNT = 64, GROUP_BYTES = 128;
    public static final int FCB_BYTES = 128;

    public DiskGeometry {
        if (blockSize != 512 && blockSize != 1024 && blockSize != 2048 && blockSize != 4096)
            throw new IllegalArgumentException("Unsupported block size");
        if (diskBytes < (1L << 20) || diskBytes > (1L << 30) || diskBytes % blockSize != 0)
            throw new IllegalArgumentException("Invalid disk capacity or alignment");
    }

    public int totalBlocks() { return Math.toIntExact(diskBytes / blockSize); }
    public int totalSectors() { return Math.toIntExact(diskBytes / SECTOR_BYTES); }
    public int startLba() { return blockSize / SECTOR_BYTES; }
    public int partitionSectors() { return totalSectors() - startLba(); }
    public int bitmapBytes() { return Math.ceilDiv(totalBlocks(), 8); }
    public int bitmapBlocks() { return blocksFor(bitmapBytes()); }
    public long bitmapRegionBytes() { return (long) bitmapBlocks() * blockSize; }
    public int userStart() { return BITMAP_START + bitmapBlocks(); }
    public int userBlocks() { return blocksFor((long) USER_COUNT * USER_BYTES); }
    public int groupStart() { return userStart() + userBlocks(); }
    public int groupBlocks() { return blocksFor((long) GROUP_COUNT * GROUP_BYTES); }
    public int fcbStart() { return groupStart() + groupBlocks(); }
    public int fcbCapacity() { return Math.toIntExact(Math.clamp(diskBytes / 4096, 128L, 16384L)); }
    public int fcbBlocks() { return blocksFor((long) fcbCapacity() * FCB_BYTES); }
    public int firstAllocatable() { return fcbStart() + fcbBlocks(); }
    public int allocatableBlocks() { return totalBlocks() - firstAllocatable(); }

    public long offset(int block) {
        if (block < 0 || block >= totalBlocks()) throw new IllegalArgumentException("Invalid block ID: " + block);
        return (long) block * blockSize;
    }

    private int blocksFor(long bytes) { return Math.toIntExact(Math.ceilDiv(bytes, blockSize)); }
}
