package io.github.rajami1205.osimulator.presentation;

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
        Parent root = FXMLLoader.load(viewResource);
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
