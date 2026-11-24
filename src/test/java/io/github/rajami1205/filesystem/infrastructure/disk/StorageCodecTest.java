package io.github.rajami1205.filesystem.infrastructure.disk;

import io.github.rajami1205.filesystem.model.disk.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageCodecTest {
    private final DiskGeometry g = new DiskGeometry(1 << 20, 512);

    @Test void activeAndFreeGoldenBytes() {
        byte[] golden = new byte[128];
        byte[] prefix = HexFormat.of().parseHex(
                "01033f0001000000020000000300000004000000000200000102000000000000"
                + "08070605040302011817161514131211ffffffffffffffff");
        System.arraycopy(prefix, 0, golden, 0, prefix.length);
        Fcb fcb = new Fcb(Fcb.Type.SYMBOLIC_LINK, 63, 1, 2, 3, 4, 512, 513,
                0x0102030405060708L, 0x1112131415161718L);
        assertEquals(fcb, StorageCodec.readFcb(golden, 0, g, 1));
        byte[] encoded = new byte[128];
        StorageCodec.writeFcb(encoded, 0, fcb);
        assertArrayEquals(golden, encoded);
        for (int offset : new int[]{0, 1, 3, 4, 8, 12, 17, 23, 31, 48, 127}) {
            byte[] bad = golden.clone();
            bad[offset] ^= (byte) 0x80;
            assertThrows(IllegalArgumentException.class, () -> StorageCodec.readFcb(bad, 0, g, 1), "offset " + offset);
        }
        StorageCodec.writeFcb(encoded, 0, null);
        assertArrayEquals(new byte[128], encoded);
        assertNull(StorageCodec.readFcb(encoded, 0, g, 0));
        encoded[127] = 1;
        assertThrows(IllegalArgumentException.class, () -> StorageCodec.readFcb(encoded, 0, g, 0));
    }

    @Test void indexGoldenBytesAndMalformedFields() {
        byte[] golden = new byte[512];
        Arrays.fill(golden, (byte) -1);
        byte[] head = HexFormat.of().parseHex("49445831ffffffff02000000000000000102000003020000");
        System.arraycopy(head, 0, golden, 0, head.length);
        IndexBlock index = new IndexBlock(-1, List.of(513, 515));
        assertEquals(index, StorageCodec.readIndex(golden, g));
        byte[] encoded = new byte[512];
        StorageCodec.writeIndex(encoded, index, g);
        assertArrayEquals(golden, encoded);
        for (int i : new int[]{0, 4, 9, 12, 19, 511}) {
            byte[] bad = golden.clone();
            bad[i] ^= 1;
            assertThrows(IllegalArgumentException.class, () -> StorageCodec.readIndex(bad, g));
        }
    }

    @Test void completeAllocationAndReleaseBatches() {
        var empty = AllocationBitmap.initial(g);
        int first = g.firstAllocatable();
        var batch = empty.allocate(3);
        assertEquals(List.of(first, first + 1, first + 2), batch.blocks());
        assertFalse(empty.occupied(first));
        var freed = batch.bitmap().release(List.of(first, first + 2));
        assertEquals(List.of(first, first + 2, first + 3), freed.allocate(3).blocks());
        for (var invalid : List.of(List.of(first + 1, first), List.of(first + 1, first + 1),
                List.of(0), List.of(g.totalBlocks()))) {
            assertThrows(IllegalArgumentException.class, () -> freed.release(invalid));
            assertTrue(freed.occupied(first + 1));
        }
        assertThrows(IllegalArgumentException.class, () -> freed.allocate(g.allocatableBlocks()));
    }
}
