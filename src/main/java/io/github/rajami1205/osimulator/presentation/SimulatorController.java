package io.github.rajami1205.osimulator.presentation;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.program.ProgramImporter;
import io.github.rajami1205.osimulator.application.program.exception.ProgramImportException;
import io.github.rajami1205.osimulator.application.program.exception.ProgramLoadException;
import io.github.rajami1205.osimulator.application.simulator.SimulatorOrchestrator;
import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot;
import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot.ProgramEntry;
import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot.MemoryEntry;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Slider;
import java.nio.file.Path;
import java.util.Objects;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.util.Duration;

/** Gestiona las acciones manuales de la interfaz y renderiza snapshots inmutables de la aplicación. */
public final class SimulatorController {
    private static final Duration AUTOMATIC_STEP_INTERVAL = Duration.millis(750);
    private Timeline automaticTimeline;
    private boolean automaticMode;
    private final SimulatorOrchestrator orchestrator;
    private final ProgramImporter programImporter;
    private Path selectedProgramPath;

    @FXML private Label simulatorStateLabel;
    @FXML private Label pcValueLabel;
    @FXML private Label irValueLabel;
    @FXML private Label acValueLabel;
    @FXML private Label axValueLabel;
    @FXML private Label bxValueLabel;
    @FXML private Label cxValueLabel;
    @FXML private Label dxValueLabel;
    @FXML private Label currentInstructionLabel;
    @FXML private Label opcodeValueLabel;
    @FXML private Label operandValueLabel;
    @FXML private Label executionStatusLabel;
    @FXML private Label processIdValueLabel;
    @FXML private Label processStateValueLabel;
    @FXML private Label processStartValueLabel;
    @FXML private Label processInstructionCountValueLabel;
    @FXML private Label processEndValueLabel;
    @FXML private Label processSavedPcValueLabel;
    @FXML private Slider mainMemorySlider;
    @FXML private Slider kernelMemorySlider;
    @FXML private Slider secondaryStorageSlider;
    @FXML private Slider virtualMemorySlider;
    @FXML private Label mainMemoryValue;
    @FXML private Label kernelMemoryValue;
    @FXML private Label secondaryStorageValue;
    @FXML private Label virtualMemoryValue;
    @FXML private Label mainMemoryRange;
    @FXML private Label kernelMemoryRange;
    @FXML private Label secondaryStorageRange;
    @FXML private Label virtualMemoryRange;
    @FXML private TextField programPathField;
    @FXML private Button initializeButton;
    @FXML private Button browseProgramButton;
    @FXML private Button loadProgramButton;
    @FXML private Button startButton;
    @FXML private Button stepButton;
    @FXML private Button automaticButton;
    @FXML private Button pauseButton;
    @FXML private Button resumeButton;
    @FXML private Button resetButton;
    @FXML private TableView<ProgramEntry> programTable;
    @FXML private TableColumn<ProgramEntry, Number> programAddressColumn;
    @FXML private TableColumn<ProgramEntry, String> programInstructionColumn;
    @FXML private TableView<MemoryEntry> memoryTable;
    @FXML private TableColumn<MemoryEntry, Number> memoryAddressColumn;
    @FXML private TableColumn<MemoryEntry, String> memoryRegionColumn;
    @FXML private TableColumn<MemoryEntry, String> memoryContentColumn;

    // Recibe las dependencias de aplicación utilizadas por la vista.
    public SimulatorController(SimulatorOrchestrator orchestrator, ProgramImporter programImporter) {
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator must not be null");
        this.programImporter = Objects.requireNonNull(programImporter, "programImporter must not be null");
    }

    @FXML
    // Configura el Timeline y las tablas antes de renderizar la sesión inicial.
    private void initialize() {
        configureSlider(mainMemorySlider, mainMemoryValue, mainMemoryRange);
        configureSlider(kernelMemorySlider, kernelMemoryValue, kernelMemoryRange);
        configureSlider(secondaryStorageSlider, secondaryStorageValue, secondaryStorageRange);
        configureSlider(virtualMemorySlider, virtualMemoryValue, virtualMemoryRange);
        mainMemorySlider.valueProperty().addListener((observable, oldValue, newValue) ->
                updateDependentRange(kernelMemorySlider, mainMemorySlider));
        secondaryStorageSlider.valueProperty().addListener((observable, oldValue, newValue) ->
                updateDependentRange(virtualMemorySlider, secondaryStorageSlider));
        restoreConfigurationDefaults();
        automaticTimeline = new Timeline(new KeyFrame(AUTOMATIC_STEP_INTERVAL, event -> {
            if (automaticMode && orchestrator.snapshot().simulatorState() == SimulatorState.RUNNING) {
                executeSingleStep();
            }
        }));
        automaticTimeline.setCycleCount(Timeline.INDEFINITE);
        programAddressColumn.setCellValueFactory(cell -> new ReadOnlyIntegerWrapper(cell.getValue().address()));
        programInstructionColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().instruction()));
        memoryAddressColumn.setCellValueFactory(cell -> new ReadOnlyIntegerWrapper(cell.getValue().address()));
        memoryRegionColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().region()));
        memoryContentColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().content().orElse("—")));
        render(orchestrator.snapshot());
    }

    @FXML
    // Valida la entrada de memoria e inicializa la sesión, mostrando errores recuperables.
    private void handleInitialize() {
        try {
            orchestrator.initialize(new SimulatorConfiguration(
                    new MemoryConfiguration(sliderValue(mainMemorySlider), sliderValue(kernelMemorySlider)),
                    sliderValue(secondaryStorageSlider), sliderValue(virtualMemorySlider)));
        } catch (IllegalArgumentException exception) {
            showError("Invalid simulator configuration. " + exception.getMessage());
        } catch (IllegalStateException exception) {
            showError("Operation unavailable. " + exception.getMessage());
        } finally {
            render(orchestrator.snapshot());
        }
    }

    @FXML
    // Permite seleccionar el archivo ASM que se importará.
    private void handleBrowseProgram() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select ASM program");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Assembly files (*.asm)", "*.asm"));
        var selectedFile = chooser.showOpenDialog(programPathField.getScene().getWindow());
        if (selectedFile != null) {
            selectedProgramPath = selectedFile.toPath();
            programPathField.setText(selectedProgramPath.toString());
        }
        updateControls(orchestrator.snapshot());
    }

    @FXML
    // Importa y carga el programa seleccionado, conservando la sesión si se rechaza.
    private void handleLoadProgram() {
        if (selectedProgramPath == null) {
            showError("Select an ASM program first.");
            render(orchestrator.snapshot());
            return;
        }
        try {
            var instructions = programImporter.importProgram(selectedProgramPath);
            orchestrator.loadProgram(instructions);
        } catch (ProgramImportException exception) {
            showError("Unable to import ASM program. " + exception.getMessage());
        } catch (ProgramLoadException exception) {
            showError("Unable to load program. " + exception.getMessage());
        } catch (IllegalStateException exception) {
            showError("Operation unavailable. " + exception.getMessage());
        } finally {
            render(orchestrator.snapshot());
        }
    }

    @FXML
    // Pasa a RUNNING sin adelantar ninguna instrucción.
    private void handleStart() {
        runStateOperation(orchestrator::start);
    }

    @FXML
    // Solicita una instrucción mediante el camino compartido de ejecución.
    private void handleStep() {
        executeSingleStep();
    }

    @FXML
    // Activa el avance periódico cuando la sesión está en RUNNING manual.
    private void handleAutomatic() {
        var snapshot = orchestrator.snapshot();
        if (snapshot.simulatorState() != SimulatorState.RUNNING || automaticMode) {
            return;
        }
        automaticMode = true;
        render(snapshot);
        automaticTimeline.playFromStart();
    }

    // Ejecuta un paso, actualiza la vista y detiene Automatic al finalizar o fallar.
    private void executeSingleStep() {
        try {
            orchestrator.step();
        } catch (ExecutionEngineException exception) {
            stopAutomaticExecution();
            render(orchestrator.snapshot());
            showError("Execution failed. " + exception.getMessage());
            return;
        } catch (IllegalStateException exception) {
            stopAutomaticExecution();
            render(orchestrator.snapshot());
            showError("Operation unavailable. " + exception.getMessage());
            return;
        }
        var snapshot = orchestrator.snapshot();
        if (snapshot.simulatorState() == SimulatorState.FINISHED) {
            stopAutomaticExecution();
        }
        render(snapshot);
    }

    // Detiene el Timeline y descarta el modo de reproducción automática.
    private void stopAutomaticExecution() {
        automaticTimeline.stop();
        automaticMode = false;
    }

    @FXML
    // Pausa la sesión y el Timeline conservando el modo de ejecución.
    private void handlePause() {
        if (automaticMode) {
            automaticTimeline.pause();
        }
        runStateOperation(orchestrator::pause);
    }

    @FXML
    // Reanuda la sesión y retoma el Timeline solo si el modo era automático.
    private void handleResume() {
        try {
            orchestrator.resume();
        } catch (IllegalStateException exception) {
            stopAutomaticExecution();
            render(orchestrator.snapshot());
            showError("Operation unavailable. " + exception.getMessage());
            return;
        }
        if (automaticMode) {
            automaticTimeline.play();
        }
        render(orchestrator.snapshot());
    }

    @FXML
    // Detiene Automatic y limpia la sesión, la selección y las entradas de la vista.
    private void handleReset() {
        stopAutomaticExecution();
        orchestrator.reset();
        selectedProgramPath = null;
        programPathField.clear();
        restoreConfigurationDefaults();
        render(orchestrator.snapshot());
    }

    // Aplica una transición de sesión y refleja su resultado o error en la vista.
    private void runStateOperation(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalStateException exception) {
            showError("Operation unavailable. " + exception.getMessage());
        } finally {
            render(orchestrator.snapshot());
        }
    }

    // Actualiza los valores y controles visibles a partir de un snapshot.
    private void render(SimulatorSnapshot snapshot) {
        simulatorStateLabel.setText(snapshot.simulatorState().name());
        renderCpu(snapshot);
        renderCurrentInstruction(snapshot);
        renderProcess(snapshot);
        programTable.setItems(FXCollections.observableArrayList(snapshot.program()));
        memoryTable.setItems(FXCollections.observableArrayList(snapshot.memory()));
        updateControls(snapshot);
    }

    // Muestra los registros reales de la CPU o su ausencia en la sesión.
    private void renderCpu(SimulatorSnapshot snapshot) {
        var cpu = snapshot.cpu();
        pcValueLabel.setText(cpu.map(value -> Integer.toString(value.programCounter())).orElse("—"));
        irValueLabel.setText(cpu.flatMap(value -> value.instructionRegister()).orElse("—"));
        acValueLabel.setText(cpu.map(value -> Integer.toString(value.accumulator())).orElse("—"));
        axValueLabel.setText(cpu.map(value -> Integer.toString(value.ax())).orElse("—"));
        bxValueLabel.setText(cpu.map(value -> Integer.toString(value.bx())).orElse("—"));
        cxValueLabel.setText(cpu.map(value -> Integer.toString(value.cx())).orElse("—"));
        dxValueLabel.setText(cpu.map(value -> Integer.toString(value.dx())).orElse("—"));
    }

    // Muestra la instrucción semántica y el estado de ejecución.
    private void renderCurrentInstruction(SimulatorSnapshot snapshot) {
        var instruction = snapshot.currentInstruction();
        currentInstructionLabel.setText(instruction.map(value -> value.semanticInstruction()).orElse("—"));
        opcodeValueLabel.setText(instruction.map(value -> value.opcode()).orElse("—"));
        operandValueLabel.setText(instruction.map(value -> value.operand()).orElse("—"));
        executionStatusLabel.setText(switch (snapshot.simulatorState()) {
            case ERROR -> "Execution error";
            case FINISHED -> "Execution finished";
            case PAUSED -> "Execution paused";
            case RUNNING -> automaticMode ? "Automatic execution"
                    : instruction.isPresent() ? "Last executed instruction" : "Waiting for execution";
            default -> "Waiting for execution";
        });
    }

    // Muestra los metadatos y el estado del PCB disponible.
    private void renderProcess(SimulatorSnapshot snapshot) {
        var process = snapshot.process();
        processIdValueLabel.setText(process.map(value -> Integer.toString(value.processId())).orElse("—"));
        processStateValueLabel.setText(process.map(value -> value.processState()).orElse("—"));
        processStartValueLabel.setText(process.map(value -> Integer.toString(value.startAddress())).orElse("—"));
        processInstructionCountValueLabel.setText(process.map(value -> Integer.toString(value.instructionCount())).orElse("—"));
        processEndValueLabel.setText(process.map(value -> Integer.toString(value.endExclusive())).orElse("—"));
        processSavedPcValueLabel.setText(process.map(value -> Integer.toString(value.savedProgramCounter())).orElse("—"));
    }

    // Habilita las acciones permitidas por el estado de sesión y el modo automático.
    private void updateControls(SimulatorSnapshot snapshot) {
        SimulatorState state = snapshot.simulatorState();
        boolean configuring = state == SimulatorState.CONFIGURING;
        initializeButton.setDisable(!configuring);
        browseProgramButton.setDisable(!configuring && state != SimulatorState.INITIALIZED);
        loadProgramButton.setDisable(state != SimulatorState.INITIALIZED || selectedProgramPath == null);
        startButton.setDisable(state != SimulatorState.PROGRAM_LOADED);
        stepButton.setDisable(state != SimulatorState.RUNNING || automaticMode);
        pauseButton.setDisable(state != SimulatorState.RUNNING);
        resumeButton.setDisable(state != SimulatorState.PAUSED);
        resetButton.setDisable(false);
        automaticButton.setDisable(state != SimulatorState.RUNNING || automaticMode);
        mainMemorySlider.setDisable(!configuring);
        kernelMemorySlider.setDisable(!configuring);
        secondaryStorageSlider.setDisable(!configuring);
        virtualMemorySlider.setDisable(!configuring);
        programPathField.setEditable(false);
    }

    private static int sliderValue(Slider slider) {
        return (int) Math.round(slider.getValue());
    }

    // Normaliza también el arrastre y mantiene el valor mostrado igual al enviado.
    private static void configureSlider(Slider slider, Label value, Label range) {
        slider.valueProperty().addListener((observable, oldValue, newValue) -> {
            double integer = Math.round(newValue.doubleValue());
            if (integer != newValue.doubleValue()) {
                slider.setValue(integer);
            }
        });
        value.textProperty().bind(Bindings.createStringBinding(
                () -> Integer.toString(sliderValue(slider)), slider.valueProperty()));
        range.textProperty().bind(Bindings.createStringBinding(
                () -> (int) slider.getMin() + " – " + (int) slider.getMax(),
                slider.minProperty(), slider.maxProperty()));
    }

    private static void updateDependentRange(Slider dependent, Slider capacity) {
        dependent.setMax(sliderValue(capacity) - 1);
        dependent.setValue(Math.min(sliderValue(dependent), dependent.getMax()));
    }

    private void restoreConfigurationDefaults() {
        var defaults = SimulatorConfiguration.defaults();
        mainMemorySlider.setValue(defaults.mainMemory().totalPositions());
        secondaryStorageSlider.setValue(defaults.secondaryStoragePositions());
        updateDependentRange(kernelMemorySlider, mainMemorySlider);
        updateDependentRange(virtualMemorySlider, secondaryStorageSlider);
        kernelMemorySlider.setValue(defaults.mainMemory().kernelReservedPositions());
        virtualMemorySlider.setValue(defaults.virtualMemoryPositions());
    }
    // Presenta un error sin bloquear el callback de JavaFX.
    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(simulatorStateLabel.getScene().getWindow());
        alert.setTitle("Operating System Simulator");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.show();
    }
}
