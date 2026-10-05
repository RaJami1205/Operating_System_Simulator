package io.github.rajami1205.osimulator.presentation;

import java.util.List;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;

/**
 * Configura celdas, tooltips y estilos de filas con valores de snapshots; no decide allocations ni
 * scheduling.
 */
final class DashboardTables {
    /** Impide instanciar el helper de configuración de tablas. */
    private DashboardTables() {}
    /** Conecta la extracción de texto con celdas y tooltips para conservar acceso a valores largos. */
    static <T> void column(TableColumn<T, String> column, Function<T, String> text) {
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            /**
             * Actualiza la celda o fila reutilizada y limpia texto/tooltips o estilos anteriores cuando
             * queda vacía.
             */
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                setTooltip(empty || value == null ? null : new Tooltip(value));
            }
        });
    }
    /** Aplica el estilo de región a cada fila y elimina estilos residuales al reutilizarla. */
    static <T> void regions(TableView<T> table, Function<T, String> style) {
        table.setRowFactory(ignored -> new TableRow<>() {
            /**
             * Actualiza la celda o fila reutilizada y limpia texto/tooltips o estilos anteriores cuando
             * queda vacía.
             */
            @Override protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll(List.of("kernel-row", "user-row", "file-index-row", "program-data-row", "virtual-memory-row"));
                if (!empty && item != null) getStyleClass().add(style.apply(item));
            }
        });
    }
    /** Muestra un valor técnico con tooltip completo y permite clipping seguro en un ancho reducido. */
    static void value(Label label, String text) {
        label.setText(text);
        label.setMinWidth(0);
        label.setTooltip(new Tooltip(text));
    }
}
