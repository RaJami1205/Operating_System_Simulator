package io.github.rajami1205.filesystem.spike;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Child JVM fixture for the client-exit integration test. */
public final class ProcessProbe {
    private static final InetAddress LOCAL = InetAddress.getLoopbackAddress();

    public static void main(String[] args) throws Exception {
        Path state = Path.of(args[1]).toAbsolutePath();
        switch (args[0]) {
            case "start" -> {
                String classes = Path.of(ProcessProbe.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).toAbsolutePath().toString();
                Process owner = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-cp", classes, ProcessProbe.class.getName(), "owner", state.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                owner.getOutputStream().close();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!Files.exists(state) && owner.isAlive() && System.nanoTime() < deadline) {
                    Thread.sleep(25);
                }
                try {
                    String identity = request(state, "ping");
                    if (!identity.startsWith(owner.pid() + "/")) {
                        throw new IllegalStateException("Readiness reply came from a different process");
                    }
                    System.out.println("READY " + identity);
                } catch (Exception ex) {
                    owner.destroyForcibly();
                    throw ex;
                }
            }
            case "owner" -> serve(state);
            default -> throw new IllegalArgumentException("Expected start or owner");
        }
    }

    static String request(Path state, String operation) throws Exception {
        int port = Integer.parseInt(Files.readString(state));
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(LOCAL, port), 2000);
            socket.setSoTimeout(2000);
            String challenge = UUID.randomUUID().toString();
            var output = new DataOutputStream(socket.getOutputStream());
            output.writeUTF(operation);
            output.writeUTF(challenge);
            var input = new DataInputStream(socket.getInputStream());
            if (!challenge.equals(input.readUTF())) throw new IllegalStateException("Wrong reply");
            return input.readUTF();
        }
    }

    private static void serve(Path state) throws Exception {
        String identity = ProcessHandle.current().pid() + "/" + UUID.randomUUID();
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(3);
        try (ServerSocket server = new ServerSocket(0, 4, LOCAL)) {
            Path temp = Files.createTempFile(state.getParent(), "port-", ".tmp");
            try {
                Files.writeString(temp, Integer.toString(server.getLocalPort()));
                Files.move(temp, state, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
            try {
                while (System.nanoTime() < deadline) {
                    server.setSoTimeout((int)Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                    try (Socket socket = server.accept()) {
                        socket.setSoTimeout(2000);
                        var input = new DataInputStream(socket.getInputStream());
                        String operation = input.readUTF();
                        var output = new DataOutputStream(socket.getOutputStream());
                        output.writeUTF(input.readUTF());
                        boolean stop = operation.equals("stop " + identity);
                        output.writeUTF(stop || operation.equals("ping") ? identity : "REJECTED");
                        if (stop) break;
                    }
                }
            } finally {
                Files.deleteIfExists(state);
            }
        }
    }
}
