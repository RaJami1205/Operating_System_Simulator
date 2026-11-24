package io.github.rajami1205.filesystem.infrastructure.disk;

import io.github.rajami1205.filesystem.model.disk.DiskGeometry;
import java.io.File;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.abort;

class DiskLockIT {
    @Test void rejectedOpenPreservesOriginalLockUntilClose(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("locked.fs");
        try (VirtualDisk owner = VirtualDisk.create(path, new DiskGeometry(1L << 20, 512), "lock")) {
            assertThrows(IOException.class, () -> VirtualDisk.open(path));
            assertEquals(2, independentOpen(path));
            assertEquals(512, owner.readBlock(0).length);
            assertEquals(2, independentOpen(path));
        }
        assertEquals(0, independentOpen(path));
    }

    @Test void renameAndReplacementKeepPhysicalOwnersSeparate(@TempDir Path dir) throws Exception {
        Path original = dir.resolve("a.fs");
        Path renamed = dir.resolve("renamed.fs");
        Path unrelated = dir.resolve("unrelated.fs");
        DiskGeometry small = new DiskGeometry(1L << 20, 512);
        DiskGeometry large = new DiskGeometry(2L << 20, 512);
        try (VirtualDisk ignored = VirtualDisk.create(unrelated, large, "unrelated")) {}
        try (VirtualDisk owner = VirtualDisk.create(original, small, "original")) {
            boolean hasKey = Files.readAttributes(original, BasicFileAttributes.class).fileKey() != null;
            try {
                Files.move(original, renamed);
            } catch (AccessDeniedException ex) {
                if (!System.getProperty("os.name").startsWith("Windows")) throw ex;
                abort("Host denied rename of an open image: " + ex);
            }
            // The previously registered name no longer exists at this point.
            if (hasKey) {
                try (VirtualDisk separate = VirtualDisk.open(unrelated)) {
                    assertEquals(large, separate.geometry());
                }
            } else {
                assertThrows(IOException.class, () -> VirtualDisk.open(unrelated));
            }
            try (VirtualDisk replacement = VirtualDisk.create(original, large, "replacement")) {
                assertEquals(large, replacement.geometry());
                assertEquals(small, owner.geometry());
                assertThrows(IOException.class, () -> VirtualDisk.open(renamed));
                assertEquals(2, independentOpen(renamed));
            }
            if (hasKey) {
                try (VirtualDisk reopened = VirtualDisk.open(original)) {
                    assertEquals(large, reopened.geometry());
                }
            } else {
                assertThrows(IOException.class, () -> VirtualDisk.open(original));
            }
            assertEquals(0, independentOpen(original));
            assertEquals(2, independentOpen(renamed));
        }
        assertEquals(0, independentOpen(renamed));
    }

    @Test void hardLinkCannotReleaseOwnersLock(@TempDir Path dir) throws Exception {
        Path image = dir.resolve("image.fs");
        Path alias = dir.resolve("alias.fs");
        try (VirtualDisk ignored = VirtualDisk.create(image, new DiskGeometry(1L << 20, 512), "alias")) {
            try {
                Files.createLink(alias, image);
            } catch (UnsupportedOperationException ex) {
                abort("Host does not support hard links: " + ex);
            }
            assertThrows(IOException.class, () -> VirtualDisk.open(alias));
            assertEquals(2, independentOpen(alias));
        }
        assertEquals(0, independentOpen(alias));
    }

    private static int independentOpen(Path path) throws Exception {
        String cp = Path.of("target/test-classes").toAbsolutePath() + File.pathSeparator
                + Path.of("target/classes").toAbsolutePath();
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", cp, Reader.class.getName(), path.toString()).inheritIO().start();
        try {
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            return child.exitValue();
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            }
        }
    }

    public static class Reader {
        public static void main(String[] args) throws IOException {
            try (VirtualDisk disk = VirtualDisk.open(Path.of(args[0]))) {
                disk.readBlock(0);
            } catch (IOException ex) {
                if (!"Image already locked".equals(ex.getMessage())) throw ex;
                System.exit(2);
            }
        }
    }
}
