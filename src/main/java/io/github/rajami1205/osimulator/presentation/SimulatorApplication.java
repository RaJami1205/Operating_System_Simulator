package io.github.rajami1205.osimulator.presentation;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

public class SimulatorApplication extends Application {

    @Override
    public void start(Stage primaryStage) {
        Label label = new Label("Operating System Simulator");

        StackPane root = new StackPane(label);
        Scene scene = new Scene(root, 800, 500);

        primaryStage.setTitle("Operating System Simulator");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}