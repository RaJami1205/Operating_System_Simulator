/*
 * Compone el simulador y presenta su ventana principal con JavaFX.
 */
package io.github.rajami1205.osimulator.presentation;

import io.github.rajami1205.osimulator.application.program.ProgramImporter;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.simulator.SimulatorOrchestrator;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmFileProgramImporter;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** Punto de entrada JavaFX: compone dependencias mediante constructor injection y carga el dashboard FXML. */
public class SimulatorApplication extends Application {

    /** Compone las dependencias, carga el FXML y muestra la ventana principal. */
    @Override
    public void start(Stage primaryStage) throws IOException {
        URL viewResource = Objects.requireNonNull(
                SimulatorApplication.class.getResource(
                        "/io/github/rajami1205/osimulator/presentation/SimulatorView.fxml"),
                "SimulatorView.fxml resource is required");
        SimulatorOrchestrator orchestrator = new SimulatorOrchestrator(
                new ProgramLoader(), new ExecutionEngine());
        ProgramImporter programImporter = new AsmFileProgramImporter(new AsmParser());
        SimulatorController controller = new SimulatorController(orchestrator, programImporter);
        FXMLLoader loader = new FXMLLoader(viewResource);
        loader.setController(controller);
        Parent root = loader.load();
        Scene scene = new Scene(root, 1280, 720);

        primaryStage.setTitle("Operating System Simulator");
        primaryStage.setScene(scene);
        primaryStage.setResizable(true);
        primaryStage.setMinWidth(1100);
        primaryStage.setMinHeight(650);
        primaryStage.show();
    }

    /** Inicia la aplicación de escritorio mediante JavaFX. */
    public static void main(String[] args) {
        launch(args);
    }
}
