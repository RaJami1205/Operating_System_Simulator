package io.github.rajami1205.osimulator.presentation;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.program.ProgramImporter;
import io.github.rajami1205.osimulator.application.program.exception.ProgramImportException;
import io.github.rajami1205.osimulator.application.simulator.*;
import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot.*;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.model.job.Job;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.storage.exception.StorageException;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import java.nio.file.Path;
import java.util.Objects;
import javafx.animation.*;
import javafx.beans.binding.Bindings;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import static io.github.rajami1205.osimulator.presentation.DashboardTables.*;

/** Commands go to Application; a single immutable observation drives each refresh. */
public final class SimulatorController {
    private static final Duration AUTOMATIC_STEP_INTERVAL = Duration.millis(750);
    private final SimulatorOrchestrator orchestrator;
    private final ProgramImporter programImporter;
    private Timeline automaticTimeline;
    private boolean automaticMode;
    private Path selectedProgramPath;
    private Integer selectedProcessId;
    private SimulatorSnapshot view;
    private boolean renderingSelection;
    private String feedback = "";
    @FXML private Label simulatorStateLabel;
    @FXML private Label runtimeStatusLabel;
    @FXML private Label ownerValueLabel;
    @FXML private Label cpuOwnerLabel;
    @FXML private Label pcValueLabel;
    @FXML private Label irValueLabel;
    @FXML private Label acValueLabel;
    @FXML private Label axValueLabel;
    @FXML private Label bxValueLabel;
    @FXML private Label cxValueLabel;
    @FXML private Label dxValueLabel;
    @FXML private Label ahValueLabel;
    @FXML private Label alValueLabel;
    @FXML private Label equalValueLabel;
    @FXML private Label overflowValueLabel;
    @FXML private Label currentInstructionLabel;
    @FXML private Label opcodeValueLabel;
    @FXML private Label operandValueLabel;
    @FXML private Label executionStatusLabel;
    @FXML private Label statusLabel;
    @FXML private Label ioStatusLabel;
    @FXML private Label mainMemoryValue;
    @FXML private Label kernelMemoryValue;
    @FXML private Label secondaryStorageValue;
    @FXML private Label virtualMemoryValue;
    @FXML private Label mainMemoryRange;
    @FXML private Label kernelMemoryRange;
    @FXML private Label secondaryStorageRange;
    @FXML private Label virtualMemoryRange;
    @FXML private Slider mainMemorySlider;
    @FXML private Slider kernelMemorySlider;
    @FXML private Slider secondaryStorageSlider;
    @FXML private Slider virtualMemorySlider;
    @FXML private Button initializeButton;
    @FXML private Button browseProgramButton;
    @FXML private Button loadProgramButton;
    @FXML private Button startButton;
    @FXML private Button stepButton;
    @FXML private Button automaticButton;
    @FXML private Button pauseButton;
    @FXML private Button resumeButton;
    @FXML private Button resetButton;
    @FXML private Button keyboardSendButton;
    @FXML private TextField programPathField;
    @FXML private TextField keyboardField;
    @FXML private TextArea pcbDetailArea;
    @FXML private TextArea completedDetailArea;
    @FXML private ListView<String> readyList;
    @FXML private ListView<String> suspendedList;
    @FXML private ListView<String> pendingList;
    @FXML private ListView<String> screenList;
    @FXML private TableView<ProgramEntry> programTable;
    @FXML private TableColumn<ProgramEntry, String> programAddressColumn;
    @FXML private TableColumn<ProgramEntry, String> programInstructionColumn;
    @FXML private TableView<MemoryEntry> memoryTable;
    @FXML private TableColumn<MemoryEntry, String> memoryAddressColumn;
    @FXML private TableColumn<MemoryEntry, String> memoryRegionColumn;
    @FXML private TableColumn<MemoryEntry, String> memoryContentColumn;
    @FXML private TableView<StorageEntry> storageTable;
    @FXML private TableColumn<StorageEntry, String> storageAddressColumn;
    @FXML private TableColumn<StorageEntry, String> storageRegionColumn;
    @FXML private TableColumn<StorageEntry, String> storageContentColumn;
    @FXML private TableView<Job> jobsTable;
    @FXML private TableColumn<Job, String> jobIdColumn;
    @FXML private TableColumn<Job, String> jobNameColumn;
    @FXML private TableColumn<Job, String> jobStateColumn;
    @FXML private TableView<ProcessDetails> processTable;
    @FXML private TableColumn<ProcessDetails, String> pidColumn;
    @FXML private TableColumn<ProcessDetails, String> processStateColumn;
    @FXML private TableColumn<ProcessDetails, String> processPcColumn;
    @FXML private TableColumn<ProcessDetails, String> residencyColumn;
    @FXML private TableColumn<ProcessDetails, String> baseColumn;
    @FXML private TableColumn<ProcessDetails, String> limitColumn;
    @FXML private TableColumn<ProcessDetails, String> priorityColumn;
    @FXML private TableView<CompletedProcess> completedTable;
    @FXML private TableColumn<CompletedProcess, String> completedPidColumn;
    @FXML private TableColumn<CompletedProcess, String> completedPcColumn;
    @FXML private TableColumn<CompletedProcess, String> completedIrColumn;

    public SimulatorController(SimulatorOrchestrator orchestrator, ProgramImporter programImporter) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.programImporter = Objects.requireNonNull(programImporter);
    }
    @FXML private void initialize() {
        configureSlider(mainMemorySlider, mainMemoryValue, mainMemoryRange);
        configureSlider(kernelMemorySlider, kernelMemoryValue, kernelMemoryRange);
        configureSlider(secondaryStorageSlider, secondaryStorageValue, secondaryStorageRange);
        configureSlider(virtualMemorySlider, virtualMemoryValue, virtualMemoryRange);
        mainMemorySlider.valueProperty().addListener((o, before, after) -> updateDependentRange(kernelMemorySlider, mainMemorySlider));
        secondaryStorageSlider.valueProperty().addListener((o, before, after) -> updateDependentRange(virtualMemorySlider, secondaryStorageSlider));
        restoreConfigurationDefaults();
        automaticTimeline = new Timeline(new KeyFrame(AUTOMATIC_STEP_INTERVAL, event -> {
            if (automaticMode && view.simulatorState() == SimulatorState.RUNNING) executeSingleStep();
        }));
        automaticTimeline.setCycleCount(Timeline.INDEFINITE);
        configureTables();
        processTable.getSelectionModel().selectedItemProperty().addListener((o, before, after) -> {
            if (!renderingSelection) {
                selectedProcessId = after == null ? null : after.processId();
                renderSelectedProcess();
            }
        });
        completedTable.getSelectionModel().selectedItemProperty().addListener((o, before, after) ->
                completedDetailArea.setText(after == null ? "Select a completed process"
                        : DashboardDetails.context(after.finalContext()) + DashboardDetails.accounting(after.accounting())));
        refresh();
    }
    private void configureTables() {
        column(programAddressColumn, v -> Integer.toString(v.address())); column(programInstructionColumn, ProgramEntry::instruction);
        column(memoryAddressColumn, v -> Integer.toString(v.address())); column(memoryRegionColumn, MemoryEntry::region);
        column(memoryContentColumn, v -> v.content().orElse("\u2014"));
        regions(memoryTable, v -> v.region().equals("KERNEL") ? "kernel-row" : "user-row");
        column(storageAddressColumn, v -> Integer.toString(v.address())); column(storageRegionColumn, v -> v.region().name());
        column(storageContentColumn, StorageEntry::content);
        regions(storageTable, v -> switch (v.region()) { case FILE_INDEX -> "file-index-row"; case PROGRAM_DATA -> "program-data-row"; case SWAP -> "swap-row"; });
        column(jobIdColumn, v -> Integer.toString(v.jobId())); column(jobNameColumn, Job::programName); column(jobStateColumn, v -> v.state().name());
        column(pidColumn, v -> Integer.toString(v.processId())); column(processStateColumn, v -> v.state().name());
        column(processPcColumn, v -> Integer.toString(v.savedProgramCounter())); column(residencyColumn, v -> v.residency().name());
        column(baseColumn, v -> v.base().map(Object::toString).orElse("\u2014")); column(limitColumn, v -> Integer.toString(v.limit()));
        column(priorityColumn, v -> Integer.toString(v.priority()));
        column(completedPidColumn, v -> Integer.toString(v.processId())); column(completedPcColumn, v -> Integer.toString(v.finalContext().programCounter()));
        column(completedIrColumn, v -> v.finalContext().instructionRegister().orElse("\u2014"));
    }
    @FXML private void handleInitialize() {
        command(() -> orchestrator.initialize(new SimulatorConfiguration(
                new MemoryConfiguration(sliderValue(mainMemorySlider), sliderValue(kernelMemorySlider)),
                sliderValue(secondaryStorageSlider), sliderValue(virtualMemorySlider))));
    }
    @FXML private void handleBrowseProgram() {
        if (browseProgramButton.isDisabled()) return;
        var chooser = new FileChooser();
        chooser.setTitle("Select ASM program");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Assembly files (*.asm)", "*.asm"));
        var file = chooser.showOpenDialog(programPathField.getScene().getWindow());
        if (file != null) { selectedProgramPath = file.toPath(); programPathField.setText(selectedProgramPath.toString()); }
        refresh();
    }
    @FXML private void handleLoadProgram() {
        command(() -> {
            if (selectedProgramPath == null) throw new IllegalStateException("Select an ASM program first.");
            var instructions = programImporter.importProgram(selectedProgramPath);
            var name = selectedProgramPath.getFileName().toString();
            orchestrator.submitProgram(new ProgramImage(name, instructions));
            feedback = "Submitted " + name + ". Workload ready; load another program or Start.";
        });
    }
    @FXML private void handleStart() { command(orchestrator::start); }
    @FXML private void handleStep() { executeSingleStep(); }
    @FXML private void handleAutomatic() {
        if (!runnable() || automaticMode) return;
        automaticMode = true;
        refresh();
        automaticTimeline.playFromStart();
    }
    private boolean runnable() {
        return view.simulatorState() == SimulatorState.RUNNING && view.runtimeStatus().orElse(null) == RuntimeStatus.RUNNABLE;
    }
    private void executeSingleStep() {
        if (!runnable()) { stopAutomaticExecution(); refresh(); return; }
        command(() -> {
            var result = orchestrator.step();
            if (result.status() != RuntimeStatus.RUNNABLE) stopAutomaticExecution();
        });
    }
    @FXML private void handlePause() {
        automaticTimeline.pause();
        command(orchestrator::pause);
    }
    @FXML private void handleResume() {
        command(orchestrator::resume);
        if (automaticMode && runnable()) automaticTimeline.play();
    }
    @FXML private void handleReset() {
        stopAutomaticExecution();
        selectedProgramPath = null; selectedProcessId = null;
        programPathField.clear(); keyboardField.clear(); feedback = "";
        orchestrator.reset(); restoreConfigurationDefaults(); refresh();
    }
    @FXML private void handleKeyboardSend() {
        if (keyboardSendButton.isDisabled()) return;
        command(() -> {
            String text = keyboardField.getText().trim();
            if (text.isEmpty()) throw new IllegalArgumentException("Enter an integer from 0 to 255.");
            final int input;
            try { input = Integer.parseInt(text); }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Enter an integer from 0 to 255."); }
            orchestrator.submitKeyboardInput(input);
            keyboardField.clear(); feedback = "Keyboard value accepted: " + input;
        });
    }
    @FunctionalInterface
    private interface UiCommand { void run() throws ProgramImportException; }

    private void command(UiCommand operation) {
        feedback = "";
        try { operation.run(); }
        catch (ProgramImportException | StorageException | IllegalArgumentException | IllegalStateException failure) {
            feedback = failure.getMessage(); showError(feedback);
        } catch (ExecutionEngineException failure) {
            stopAutomaticExecution(); feedback = failure.getMessage(); showError(feedback);
        } finally { refresh(); }
    }
    private void stopAutomaticExecution() { automaticTimeline.stop(); automaticMode = false; }
    private void refresh() {
        var snapshot = orchestrator.snapshot();
        if (snapshot.simulatorState() == SimulatorState.ERROR
                && (view == null || view.simulatorState() != SimulatorState.ERROR) && feedback.isBlank()) {
            feedback = "Runtime state could not be validated. Inspect the session or Reset.";
            showError(feedback);
        }
        render(snapshot);
    }
    private void render(SimulatorSnapshot snapshot) {
        view = snapshot;
        if (snapshot.simulatorState() == SimulatorState.ERROR || snapshot.simulatorState() == SimulatorState.FINISHED
                || (snapshot.simulatorState() == SimulatorState.RUNNING && snapshot.runtimeStatus().orElse(null) != RuntimeStatus.RUNNABLE)) {
            stopAutomaticExecution();
        }
        simulatorStateLabel.setText(snapshot.simulatorState().name());
        runtimeStatusLabel.setText(snapshot.runtimeStatus().map(Enum::name).orElse("\u2014"));
        ownerValueLabel.setText(snapshot.ownerPid().map(pid -> "PID " + pid).orElse("Idle"));
        cpuOwnerLabel.setText(snapshot.ownerPid().map(pid -> "Active CPU: PID " + pid).orElse("Idle / retained CPU registers"));
        renderCpu(snapshot);
        currentInstructionLabel.setText(snapshot.currentInstruction().map(InstructionSnapshot::semanticInstruction).orElse("\u2014"));
        opcodeValueLabel.setText(snapshot.currentInstruction().map(InstructionSnapshot::opcode).orElse("\u2014"));
        value(operandValueLabel, snapshot.currentInstruction().map(InstructionSnapshot::operand).orElse("\u2014"));
        executionStatusLabel.setText(statusText(snapshot));
        jobsTable.getItems().setAll(snapshot.jobs());
        renderProcesses(snapshot);
        programTable.getItems().setAll(snapshot.program()); memoryTable.getItems().setAll(snapshot.memory()); storageTable.getItems().setAll(snapshot.storage());
        readyList.getItems().setAll(snapshot.readyQueue().stream().map(pid -> "PID " + pid).toList());
        suspendedList.getItems().setAll(snapshot.suspendedReadyQueue().stream().map(pid -> "PID " + pid).toList());
        pendingList.getItems().setAll(snapshot.pendingKeyboardRequests().stream().map(r -> "PID " + r.processId() + " / PC " + r.interruptPc()).toList());
        screenList.getItems().setAll(snapshot.screenOutput().stream().map(Object::toString).toList());
        var selected = completedTable.getSelectionModel().getSelectedItem();
        completedTable.getItems().setAll(snapshot.completedProcesses());
        if (selected != null) snapshot.completedProcesses().stream().filter(p -> p.processId() == selected.processId()).findFirst()
                .ifPresent(p -> completedTable.getSelectionModel().select(p));
        if (completedTable.getSelectionModel().getSelectedItem() == null) completedDetailArea.setText("Select a completed process");
        statusLabel.setText(feedback.isBlank() ? statusText(snapshot) : feedback + " | " + statusText(snapshot));
        ioStatusLabel.setText(statusText(snapshot) + " | Pending input: " + snapshot.pendingKeyboardRequests().size());
        updateControls(snapshot);
    }
    private void renderProcesses(SimulatorSnapshot snapshot) {
        renderingSelection = true;
        try {
            processTable.getItems().setAll(snapshot.processes());
            var selected = snapshot.processes().stream().filter(p -> Objects.equals(selectedProcessId, p.processId())).findFirst();
            if (selected.isPresent()) processTable.getSelectionModel().select(selected.orElseThrow());
            else { selectedProcessId = null; processTable.getSelectionModel().clearSelection(); }
        } finally { renderingSelection = false; }
        renderSelectedProcess();
    }
    private void renderSelectedProcess() {
        pcbDetailArea.setText(view.processes().stream().filter(p -> Objects.equals(selectedProcessId, p.processId()))
                .findFirst().map(DashboardDetails::process).orElse("Select a process"));
    }
    private void renderCpu(SimulatorSnapshot snapshot) {
        var cpu = snapshot.cpu();
        value(pcValueLabel, cpu.map(v -> Integer.toString(v.programCounter())).orElse("\u2014"));
        value(irValueLabel, cpu.flatMap(CpuSnapshot::instructionRegister).orElse("\u2014"));
        value(acValueLabel, cpu.map(v -> Integer.toString(v.accumulator())).orElse("\u2014"));
        value(axValueLabel, cpu.map(v -> Integer.toString(v.ax())).orElse("\u2014"));
        value(bxValueLabel, cpu.map(v -> Integer.toString(v.bx())).orElse("\u2014"));
        value(cxValueLabel, cpu.map(v -> Integer.toString(v.cx())).orElse("\u2014"));
        value(dxValueLabel, cpu.map(CpuSnapshot::dxText).orElse("\u2014"));
        value(ahValueLabel, cpu.map(v -> Integer.toString(v.ah())).orElse("\u2014"));
        value(alValueLabel, cpu.map(CpuSnapshot::alText).orElse("\u2014"));
        value(equalValueLabel, cpu.map(v -> Boolean.toString(v.flags().equal())).orElse("\u2014"));
        value(overflowValueLabel, cpu.map(v -> Boolean.toString(v.flags().overflow())).orElse("\u2014"));
    }
    private String statusText(SimulatorSnapshot snapshot) {
        if (snapshot.simulatorState() == SimulatorState.PAUSED) return "Paused; keyboard input is queued until Resume";
        if (snapshot.simulatorState() == SimulatorState.ERROR) return "Execution error; inspect state or Reset";
        if (snapshot.runtimeStatus().isPresent()) return switch (snapshot.runtimeStatus().orElseThrow()) {
            case RUNNABLE -> automaticMode ? "Automatic execution" : "Ready for next CPU tick";
            case WAITING_FOR_INPUT -> "Waiting for keyboard input";
            case WAITING_FOR_CAPACITY -> "Waiting for capacity";
            case FINISHED -> "Workload finished";
        };
        return switch (snapshot.simulatorState()) {
            case CONFIGURING -> "Configure the simulator";
            case INITIALIZED -> "Load one or more ASM programs";
            case PROGRAM_LOADED -> "Workload ready; load another program or Start";
            default -> "\u2014";
        };
    }
    private void updateControls(SimulatorSnapshot snapshot) {
        var state = snapshot.simulatorState();
        boolean configuring = state == SimulatorState.CONFIGURING;
        boolean loading = state == SimulatorState.INITIALIZED || state == SimulatorState.PROGRAM_LOADED;
        boolean keyboard = loading || state == SimulatorState.RUNNING || state == SimulatorState.PAUSED;
        initializeButton.setDisable(!configuring); browseProgramButton.setDisable(!loading);
        loadProgramButton.setDisable(!loading || selectedProgramPath == null); startButton.setDisable(state != SimulatorState.PROGRAM_LOADED);
        stepButton.setDisable(!runnable() || automaticMode); automaticButton.setDisable(!runnable() || automaticMode);
        pauseButton.setDisable(state != SimulatorState.RUNNING); resumeButton.setDisable(state != SimulatorState.PAUSED);
        resetButton.setDisable(false); keyboardField.setDisable(!keyboard); keyboardSendButton.setDisable(!keyboard);
        mainMemorySlider.setDisable(!configuring); kernelMemorySlider.setDisable(!configuring);
        secondaryStorageSlider.setDisable(!configuring); virtualMemorySlider.setDisable(!configuring);
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

    private void showError(String message) {
        var alert = new Alert(Alert.AlertType.ERROR);
        if (simulatorStateLabel.getScene() != null) alert.initOwner(simulatorStateLabel.getScene().getWindow());
        alert.setTitle("Operating System Simulator"); alert.setHeaderText(null); alert.setContentText(message); alert.show();
    }
}
