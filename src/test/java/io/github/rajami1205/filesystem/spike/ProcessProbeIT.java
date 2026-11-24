package io.github.rajami1205.filesystem.spike;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ProcessProbeIT {
    @Test void ownerRespondsAfterClientExit(@TempDir Path dir) throws Exception {
        Path state = dir.resolve("endpoint");
        Path log = dir.resolve("client.log");
        Path errors = dir.resolve("client.err");
        Process client = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", Path.of("target/test-classes").toAbsolutePath().toString(),
                ProcessProbe.class.getName(), "start", state.toString())
                .redirectError(errors.toFile()).redirectOutput(log.toFile()).start();
        try (AutoCloseable clientCleanup = () -> {
            if (client.isAlive()) client.destroyForcibly();
            assertTrue(client.waitFor(5, TimeUnit.SECONDS), "Client did not terminate");
        }) {
            assertTrue(client.waitFor(15, TimeUnit.SECONDS));
            String ready = java.nio.file.Files.readString(log).strip();
            assertTrue(ready.matches("READY [1-9][0-9]*/[0-9a-f-]{36}"), ready);
            String identity = ready.substring("READY ".length());
            long pid = Long.parseLong(identity.substring(0, identity.indexOf('/')));
            ProcessHandle owner = ProcessHandle.of(pid).orElseThrow();
            try (AutoCloseable ownerCleanup = () -> {
                assertEquals(identity, ProcessProbe.request(state, "stop " + identity));
                owner.onExit().get(5, TimeUnit.SECONDS);
            }) {
                assertEquals(0, client.exitValue(), java.nio.file.Files.readString(errors));
                String otherIdentity = pid + "/" + java.util.UUID.randomUUID();
                assertEquals("REJECTED", ProcessProbe.request(state, "stop " + otherIdentity));
                assertEquals(identity, ProcessProbe.request(state, "ping"));
            }
        }
    }

}
