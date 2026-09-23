package io.github.rajami1205.osimulator.model.process;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Nombres lógicos abiertos por un proceso; no accede al filesystem anfitrión. */
public final class OpenFileTable {
    private final Set<String> files = new LinkedHashSet<>();

    public boolean open(String filename) {
        return files.add(validate(filename));
    }

    public boolean close(String filename) {
        return files.remove(validate(filename));
    }

    public boolean contains(String filename) {
        return files.contains(validate(filename));
    }

    public int size() {
        return files.size();
    }

    public Set<String> files() {
        return Set.copyOf(files);
    }

    private static String validate(String filename) {
        Objects.requireNonNull(filename, "filename must not be null");
        if (filename.isBlank()) {
            throw new IllegalArgumentException("Filename must not be blank");
        }
        return filename;
    }
}
