package io.github.rajami1205.osimulator.presentation;

import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import java.nio.file.Path;
import java.util.Objects;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Fila de la lista de programas por cargar: archivo elegido y el texto que el usuario escribe para su
 * Arrival y su Priority. Es estado de Presentation; el parsing y la validación ocurren al cargar.
 */
final class ProgramLoadRow {
    private final Path path;
    private final StringProperty arrival = new SimpleStringProperty("0");
    private final StringProperty priority =
            new SimpleStringProperty(Integer.toString(ProcessControlBlock.DEFAULT_PRIORITY));

    ProgramLoadRow(Path path) {
        this.path = Objects.requireNonNull(path, "path must not be null");
    }

    Path path() { return path; }

    String fileName() { return path.getFileName().toString(); }

    StringProperty arrivalProperty() { return arrival; }

    StringProperty priorityProperty() { return priority; }
}
