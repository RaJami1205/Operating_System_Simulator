package io.github.rajami1205.filesystem;

import io.github.rajami1205.filesystem.presentation.FileSystemApplication;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FoundationIT {
    @TempDir Path dir;

    @Test void extractedLauncherWorksWithOnlyDistributedFiles() throws Exception {
        Set<String> allowed = new HashSet<>(Set.of("myFileSystem.class", "README.md", "setup.ps1", "LICENSE-JLine.txt",
                "lib/jline-terminal-3.30.6.jar", "lib/jline-terminal-ffm-3.30.6.jar", "lib/jline-native-3.30.6.jar"));
        Path classes = Path.of("target/classes");
        try (var files = Files.walk(classes.resolve("io/github/rajami1205/filesystem"))) {
            files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".class"))
                    .map(p -> classes.relativize(p).toString().replace(File.separatorChar, '/'))
                    .forEach(allowed::add);
        }
        try (ZipFile zip = new ZipFile("target/filesystem.zip")) {
            var entries = zip.stream().filter(e -> !e.isDirectory()).toList();
            assertEquals(allowed, entries.stream().map(e -> e.getName()).collect(java.util.stream.Collectors.toSet()));
            for (var entry : entries) {
                Path destination = dir.resolve(entry.getName());
                Files.createDirectories(destination.getParent());
                try (var input = zip.getInputStream(entry)) {
                    Files.copy(input, destination);
                }
            }
        }
        assertEquals("myFS [unmounted]", launch(0).strip());
        for (String path : List.of("miDiscoDuro.fs", "mi disco.fs")) {
            assertTrue(launch(0, path).contains("Ruta recibida: " + path));
            assertFalse(Files.exists(dir.resolve(path)));
        }
        assertTrue(launch(2, "a", "b").contains("Uso:"));
        // Supply the exact control characters independently of the host launcher code page.
        String[] arguments = {"mi café\u001B\u007F\u009B.fs"};
        var rendered = new ByteArrayOutputStream();
        PrintStream originalOutput = System.out;
        try (var capture = new PrintStream(rendered, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            FileSystemApplication.main(arguments);
        } finally {
            System.setOut(originalOutput);
        }
        assertEquals(List.of("myFS [unmounted]", "Ruta recibida: mi café???.fs"),
                rendered.toString(StandardCharsets.UTF_8).lines().toList());
        assertEquals("mi café\u001B\u007F\u009B.fs", arguments[0]);
    }

    private String launch(int expectedExit, String... args) throws Exception {
        var command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "myFileSystem"));
        command.addAll(List.of(args));
        var builder = new ProcessBuilder(command).directory(dir.toFile());
        builder.environment().put("CLASSPATH", "." + File.pathSeparator + "lib/*");
        Path output = dir.resolve("output.txt");
        Path errors = dir.resolve("errors.txt");
        Process process = builder.redirectError(errors.toFile()).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            String diagnostic = Files.readString(errors);
            assertEquals(expectedExit, process.exitValue(), diagnostic);
            return expectedExit == 0 ? Files.readString(output) : diagnostic;
        } finally {
            process.destroyForcibly();
        }
    }

    @Test void compiledPackagesRemainIndependent() {
        var writer = new java.io.StringWriter();
        var printer = new java.io.PrintWriter(writer);
        var tool = java.util.spi.ToolProvider.findFirst("jdeps").orElseThrow();
        assertEquals(0, tool.run(printer, printer, "-verbose:class", "-filter:none", "target/classes"), writer::toString);
        String fs = "io.github.rajami1205.filesystem.";
        String legacy = "io.github.rajami1205.osimulator.";
        boolean delegated = false;
        for (String line : writer.toString().lines().toList()) {
            String[] edge = line.strip().split("\\s+->\\s+", 2);
            if (edge.length != 2) continue;
            boolean fromFs = edge[0].startsWith(fs) || edge[0].equals("myFileSystem");
            assertFalse(fromFs && (edge[1].startsWith(legacy) || edge[1].startsWith("javafx.")), line);
            assertFalse(edge[0].startsWith(legacy) && (edge[1].startsWith(fs) || edge[1].startsWith("myFileSystem ")), line);
            delegated |= edge[0].equals("myFileSystem") && edge[1].startsWith(fs);
        }
        assertTrue(delegated, "Expected compiled launcher dependency");
    }
}
