package io.github.rajami1205.filesystem.model.disk;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiskLayoutTest {
    @Test void defaultRegions() {
        DiskGeometry g = new DiskGeometry(10L << 20, 512);
        assertArrayEquals(new int[]{2, 7, 71, 87, 727, 19753, 2560, 20480},
                new int[]{DiskGeometry.BITMAP_START, g.userStart(), g.groupStart(), g.fcbStart(),
                        g.firstAllocatable(), g.allocatableBlocks(), g.fcbCapacity(), g.totalBlocks()});
        assertEquals(372224, g.offset(727));
        AllocationBitmap bitmap = AllocationBitmap.initial(g);
        assertEquals(0x7F, bitmap.bytes()[90]);
        assertTrue(bitmap.occupied(726));
        assertFalse(bitmap.occupied(727));
    }

    @Test void boundariesAndAlignment() {
        for (int block : new int[]{512, 1024, 2048, 4096}) {
            for (long bytes : new long[]{1L << 20, 1L << 30}) {
                DiskGeometry g = new DiskGeometry(bytes, block);
                assertEquals(bytes / block, g.totalBlocks());
                assertEquals(bytes / 512, g.totalSectors());
                assertEquals(bytes / 512 - block / 512, g.partitionSectors());
                assertEquals(bytes == 1L << 20 ? 256 : 16384, g.fcbCapacity());
                assertEquals(0, g.offset(0));
                assertEquals(bytes - block, g.offset(g.totalBlocks() - 1));
                assertThrows(IllegalArgumentException.class, () -> g.offset(-1));
                assertThrows(IllegalArgumentException.class, () -> g.offset(g.totalBlocks()));
            }
        }
        assertEquals(21, new DiskGeometry(1L << 20, 4096).firstAllocatable());
        assertEquals(4690, new DiskGeometry(1L << 30, 512).firstAllocatable());
        for (long bytes : new long[]{-1, 0, (1L << 20) - 512, (1L << 20) + 1, (1L << 30) + 512, Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new DiskGeometry(bytes, 512));
        for (int block : new int[]{0, 256, 513, 8192})
            assertThrows(IllegalArgumentException.class, () -> new DiskGeometry(1L << 20, block));
    }

    @Test void bitmapSeparatesDynamicBitsFromPadding() {
        DiskGeometry g = new DiskGeometry((1L << 20) + 4096, 4096); // 257 blocks
        AllocationBitmap bitmap = AllocationBitmap.initial(g);
        byte[] bytes = bitmap.bytes();
        for (int id : new int[]{0, 7, 8}) assertTrue(bitmap.occupied(id));
        assertFalse(bitmap.occupied(256));
        assertThrows(IllegalArgumentException.class, () -> bitmap.occupied(257));
        assertThrows(IllegalArgumentException.class, () -> bitmap.occupied(-1));
        assertEquals(0xFE, Byte.toUnsignedInt(bytes[32]));
        for (int i = 33; i < bytes.length; i++) assertEquals((byte) 0xFF, bytes[i]);
        bytes[4] = (byte) 0x81;
        bytes[5] = 1;
        AllocationBitmap used = AllocationBitmap.decode(g, bytes);
        for (int id : new int[]{32, 39, 40}) assertTrue(used.occupied(id));
        assertFalse(used.occupied(33));
        assertFalse(bitmap.occupied(32));
        assertThrows(IllegalArgumentException.class, () -> AllocationBitmap.decode(g, new byte[1]));
    }
}
