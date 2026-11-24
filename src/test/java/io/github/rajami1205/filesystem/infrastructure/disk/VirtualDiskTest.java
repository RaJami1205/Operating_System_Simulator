package io.github.rajami1205.filesystem.infrastructure.disk;

import io.github.rajami1205.filesystem.model.disk.DiskGeometry;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VirtualDiskTest {
    @TempDir Path dir;
    private final DiskGeometry g = new DiskGeometry((1L << 20) + 4096, 4096);

    @Test void structuralImageSurvivesReopenWithUsedResources() throws Exception {
        Path path = dir.resolve("volume.fs");
        int last = g.totalBlocks() - 1;
        byte[] data = new byte[g.blockSize()];
        Arrays.fill(data, (byte) 0xAC);
        try (VirtualDisk disk = VirtualDisk.create(path, g, "é".repeat(32))) {
            assertEquals(g.diskBytes(), Files.size(path));
            assertEquals(0, disk.readBlock(1)[208]);
            for (int id = g.userStart(); id < g.firstAllocatable(); id++)
                assertArrayEquals(new byte[g.blockSize()], disk.readBlock(id));
            disk.writeBlock(last, data);
            assertThrows(IllegalArgumentException.class, () -> disk.writeBlock(last, new byte[1]));
            assertThrows(IllegalArgumentException.class, () -> disk.writeBlock(-1, data));
            assertThrows(IllegalArgumentException.class, () -> disk.readBlock(last + 1));
            byte[] bits = disk.readBlock(2);
            bits[last / 8] |= (byte) (1 << (last % 8));
            disk.writeBlock(2, bits);
            disk.writeBlock(g.userStart(), data);
            byte[] sb = disk.readBlock(1);
            sb[208] = 1; // Structural opening recognizes FORMATTED without interpreting the tables.
            disk.writeBlock(1, sb);
        }
        try (VirtualDisk disk = VirtualDisk.open(path)) {
            assertEquals(g, disk.geometry());
            assertArrayEquals(data, disk.readBlock(last));
            assertArrayEquals(data, disk.readBlock(g.userStart()));
            assertTrue(disk.readBitmap().occupied(last));
        }
    }

    @Test void validatesBeforeCreatingAndNeverReplacesExistingFiles() throws Exception {
        Path file = dir.resolve("disk.fs");
        assertThrows(IllegalArgumentException.class, () -> VirtualDisk.create(file, g, "é".repeat(33)));
        assertThrows(IOException.class, () -> VirtualDisk.create(file, g, "\uD800"));
        assertFalse(Files.exists(file));
        byte[] original = {1, 2, 3};
        Files.write(file, original);
        assertThrows(FileAlreadyExistsException.class, () -> VirtualDisk.create(file, g, "v"));
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test void rejectsCorruptionWithoutRepairAndReleasesLock() throws Exception {
        Path file = dir.resolve("image.fs");
        try (VirtualDisk ignored = VirtualDisk.create(file, g, "x")) {}
        byte[] original = Files.readAllBytes(file);
        int sb = g.blockSize(), bitmap = sb * 2;
        int[] damagedOffsets = {0, 8, 28, 446, 450, 454, 458, 462, 511, 512,
                sb, sb + 8, sb + 12, sb + 16, sb + 20, sb + 24, sb + 32, sb + 36,
                sb + 40, sb + 44, sb + 48, sb + 56, sb + 72, sb + 88, sb + 104,
                sb + 112, sb + 129, sb + 130, sb + 131, sb + 194, sb + 208, sb + 255,
                sb + 256, bitmap, bitmap + 32, bitmap + 33};
        for (int offset : damagedOffsets) {
            byte[] bad = original.clone();
            bad[offset] ^= (byte) 0xFF;
            Files.write(file, bad);
            assertThrows(IOException.class, () -> VirtualDisk.open(file), "Byte " + offset);
            assertArrayEquals(bad, Files.readAllBytes(file), "Modified byte image at " + offset);
        }
        for (int length : new int[]{200, original.length - 1, original.length - sb}) {
            byte[] truncated = Arrays.copyOf(original, length);
            Files.write(file, truncated);
            assertThrows(IOException.class, () -> VirtualDisk.open(file));
            assertArrayEquals(truncated, Files.readAllBytes(file));
        }
        Files.write(file, original);
        try (VirtualDisk disk = VirtualDisk.open(file)) {
            assertEquals(g, disk.geometry());
        }
    }
}
