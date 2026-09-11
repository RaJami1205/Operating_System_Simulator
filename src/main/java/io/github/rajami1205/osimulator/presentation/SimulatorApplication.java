package io.github.rajami1205.osimulator.presentation;

import io.github.rajami1205.osimulator.application.program.ProgramImporter;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.simulator.SimulatorOrchestrator;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmFileProgramImporter;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.instruction.binary.InstructionBinaryCodec;
import java.io.IOException;
import java.net.URL;
import java.util.Objects;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class SimulatorApplication extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        URL viewResource = Objects.requireNonNull(
                SimulatorApplication.class.getResource(
                        "/io/github/rajami1205/osimulator/presentation/SimulatorView.fxml"),
                "SimulatorView.fxml resource is required");
        SimulatorOrchestrator orchestrator = new SimulatorOrchestrator(
                new ProgramLoader(), new ExecutionEngine(), new InstructionBinaryCodec());
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

    public static void main(String[] args) {
        launch(args);
    }
}
