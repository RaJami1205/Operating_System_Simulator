package io.github.rajami1205.filesystem.infrastructure.disk;

import io.github.rajami1205.filesystem.model.disk.DiskGeometry;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiskHeaderCodecTest {
    private static void hex(byte[] destination, int offset, String literal) {
        byte[] bytes = HexFormat.of().parseHex(literal.replace(" ", ""));
        System.arraycopy(bytes, 0, destination, offset, bytes.length);
    }

    @Test void independentDefaultV1GoldenBlocks() throws Exception {
        byte[] mbr = new byte[512];
        String uuid = "00112233445566778899aabbccddeeff";
        hex(mbr, 0, "4f534653494d4731 01000000 " + uuid);
        hex(mbr, 446, "80000000 da000000 01000000 ff4f0000");
        hex(mbr, 510, "55aa");

        byte[] sb = new byte[512];
        hex(sb, 0, "4f534653564f4c31 01000000 00010000 00020000 01000000 0000a00000000000");
        hex(sb, 32, "01000000 ff4f0000 00500000 d7020000");
        hex(sb, 48, "02000000 05000000");
        hex(sb, 56, "07000000 40000000 00010000 80000000");
        hex(sb, 72, "47000000 10000000 80000000 40000000");
        hex(sb, 88, "57000000 80020000 80000000 000a0000");
        hex(sb, 112, uuid + " 0200 c3a9");
        hex(sb, 200, "0807060504030201 00000000");
        DiskGeometry g = new DiskGeometry(10L << 20, 512);
        var header = new DiskHeaderCodec.Header(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"),
                "é", 0x0102030405060708L, false);
        assertArrayEquals(mbr, DiskHeaderCodec.mbr(g, header.uuid()));
        assertArrayEquals(sb, DiskHeaderCodec.superblock(g, header));
        assertEquals(g, DiskHeaderCodec.discover(mbr, 10L << 20));
        assertEquals(header, DiskHeaderCodec.validate(g, mbr, sb));
    }
}
