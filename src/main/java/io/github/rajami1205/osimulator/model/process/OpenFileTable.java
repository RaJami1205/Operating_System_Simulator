package io.github.rajami1205.osimulator.model.process;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Nombres lógicos abiertos por un proceso; no accede al filesystem anfitrión. */
public final class OpenFileTable {
    private final Set<String> files = new LinkedHashSet<>();

    /** Registra un nombre no vacío y devuelve si se añadió; no crea ni valida el archivo global. */
    public boolean open(String filename) {
        return files.add(validate(filename));
    }

    /** Retira el nombre validado y devuelve si pertenecía a la tabla; no elimina su contenido en storage. */
    public boolean close(String filename) {
        return files.remove(validate(filename));
    }

    /** Consulta pertenencia del nombre validado a los archivos abiertos de este proceso. */
    public boolean contains(String filename) {
        return files.contains(validate(filename));
    }

    /** Cuenta nombres abiertos del proceso, no entradas globales de FileIndex. */
    public int size() {
        return files.size();
    }

    /** Devuelve una copia inmutable de los nombres abiertos. */
    public Set<String> files() {
        return Set.copyOf(files);
    }

    /** Rechaza nombre nulo o en blanco sin normalizar ni modificar su texto. */
    private static String validate(String filename) {
        Objects.requireNonNull(filename, "filename must not be null");
        if (filename.isBlank()) {
            throw new IllegalArgumentException("Filename must not be blank");
        }
        return filename;
    }
}
