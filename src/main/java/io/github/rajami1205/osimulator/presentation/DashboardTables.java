package io.github.rajami1205.osimulator.presentation;

import java.util.List;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;

/** Widget configuration only; all displayed values come from immutable Application records. */
final class DashboardTables {
    private DashboardTables() {}
    static <T> void column(TableColumn<T, String> column, Function<T, String> text) {
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(text.apply(cell.getValue())));
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                setTooltip(empty || value == null ? null : new Tooltip(value));
            }
        });
    }
    static <T> void regions(TableView<T> table, Function<T, String> style) {
        table.setRowFactory(ignored -> new TableRow<>() {
            @Override protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll(List.of("kernel-row", "user-row", "file-index-row", "program-data-row", "swap-row"));
                if (!empty && item != null) getStyleClass().add(style.apply(item));
            }
        });
    }
    static void value(Label label, String text) {
        label.setText(text);
        label.setMinWidth(0);
        label.setTooltip(new Tooltip(text));
    }
}
