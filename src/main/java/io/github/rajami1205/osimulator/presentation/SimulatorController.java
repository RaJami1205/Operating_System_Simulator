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

/**
 * Traduce acciones JavaFX a comandos del Orchestrator y renderiza snapshots inmutables. Timeline sólo
 * marca el ritmo; aquí no residen reglas de scheduling, memoria o instrucciones.
 */
public final class SimulatorController {
    private static final Duration AUTOMATIC_STEP_INTERVAL = Duration.millis(1000);
    private final SimulatorOrchestrator orchestrator;
    private final ProgramImporter programImporter;
    private Timeline automaticTimeline;
    private boolean automaticMode;
    private Path selectedProgramPath;
    private Integer selectedProcessId;
    private SimulatorSnapshot view;
    private boolean renderingSelection;
    private String feedback = "";
    @FXML private Label cpuTicksLabel;
    @FXML private TableColumn<CompletedProcess, String> completedCpuTicksColumn;
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

    /** Recibe la facade y el importador no nulos; JavaFX inyecta los controles posteriormente. */
    public SimulatorController(SimulatorOrchestrator orchestrator, ProgramImporter programImporter) {
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.programImporter = Objects.requireNonNull(programImporter);
    }
    /**
     * Tras la inyección FXML configura sliders, tablas y listeners; Timeline invoca como máximo un Step
     * por callback y sólo regula el ritmo visual.
     */
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
    /**
     * Vincula columnas y estilos regionales a los valores inmutables del snapshot, sin consultar memoria
     * directamente.
     */
    private void configureTables() {
        column(programAddressColumn, v -> Integer.toString(v.address())); column(programInstructionColumn, ProgramEntry::instruction);
        column(memoryAddressColumn, v -> Integer.toString(v.address())); column(memoryRegionColumn, MemoryEntry::region);
        column(memoryContentColumn, v -> v.content().orElse("\u2014"));
        regions(memoryTable, v -> v.region().equals("KERNEL") ? "kernel-row" : "user-row");
        column(storageAddressColumn, v -> Integer.toString(v.address())); column(storageRegionColumn, v -> v.region().name());
        column(storageContentColumn, StorageEntry::content);
        regions(storageTable, v -> switch (v.region()) { case FILE_INDEX -> "file-index-row"; case PROGRAM_DATA -> "program-data-row"; case VIRTUAL_MEMORY -> "virtual-memory-row"; });
        column(jobIdColumn, v -> Integer.toString(v.jobId())); column(jobNameColumn, Job::programName); column(jobStateColumn, v -> v.state().name());
        column(pidColumn, v -> Integer.toString(v.processId())); column(processStateColumn, v -> v.state().name());
        column(processPcColumn, v -> Integer.toString(v.savedProgramCounter())); column(residencyColumn, v -> v.residency().name());
        column(baseColumn, v -> v.base().map(Object::toString).orElse("\u2014")); column(limitColumn, v -> Integer.toString(v.limit()));
        column(priorityColumn, v -> Integer.toString(v.priority()));
        column(completedCpuTicksColumn, v -> Long.toString(v.accounting().cpuTicks()));
        column(completedPidColumn, v -> Integer.toString(v.processId())); column(completedPcColumn, v -> Integer.toString(v.finalContext().programCounter()));
        column(completedIrColumn, v -> v.finalContext().instructionRegister().orElse("\u2014"));
    }
    /** Recoge capacidades enteras de sliders y delega validación/inicialización al Orchestrator. */
    @FXML private void handleInitialize() {
        command(() -> orchestrator.initialize(new SimulatorConfiguration(
                new MemoryConfiguration(sliderValue(mainMemorySlider), sliderValue(kernelMemorySlider)),
                sliderValue(secondaryStorageSlider), sliderValue(virtualMemorySlider))));
    }
    /** Abre el selector de ASM del host si la carga está habilitada y conserva la ruta elegida. */
    @FXML private void handleBrowseProgram() {
        if (browseProgramButton.isDisabled()) return;
        var chooser = new FileChooser();
        chooser.setTitle("Select ASM program");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Assembly files (*.asm)", "*.asm"));
        var file = chooser.showOpenDialog(programPathField.getScene().getWindow());
        if (file != null) { selectedProgramPath = file.toPath(); programPathField.setText(selectedProgramPath.toString()); }
        refresh();
    }
    /**
     * Importa ASM mediante ProgramImporter y hace submission de ProgramImage con nombre lógico; no admite
     * ni ejecuta procesos.
     */
    @FXML private void handleLoadProgram() {
        command(() -> {
            if (selectedProgramPath == null) throw new IllegalStateException("Select an ASM program first.");
            var instructions = programImporter.importProgram(selectedProgramPath);
            var name = selectedProgramPath.getFileName().toString();
            orchestrator.submitProgram(new ProgramImage(name, instructions));
            feedback = "Submitted " + name + ". Workload ready; load another program or Start.";
        });
    }
    /** Solicita Start al Orchestrator a través de la frontera común de errores y refresh. */
    @FXML private void handleStart() { command(orchestrator::start); }
    /** Ejecuta la misma ruta de un tick usada por Automatic. */
    @FXML private void handleStep() { executeSingleStep(); }
    /** Activa Timeline sólo si el snapshot está RUNNABLE y no existe ejecución automática activa. */
    @FXML private void handleAutomatic() {
        if (!runnable() || automaticMode) return;
        automaticMode = true;
        refresh();
        automaticTimeline.playFromStart();
    }
    /** Consulta si el último snapshot permite ticks: lifecycle RUNNING y runtime RUNNABLE. */
    private boolean runnable() {
        return view.simulatorState() == SimulatorState.RUNNING && view.runtimeStatus().orElse(null) == RuntimeStatus.RUNNABLE;
    }
    /** Solicita un único Step cuando es ejecutable y detiene Automatic si deja de estar RUNNABLE. */
    private void executeSingleStep() {
        if (!runnable()) { stopAutomaticExecution(); refresh(); return; }
        command(() -> {
            var result = orchestrator.step();
            if (result.status() != RuntimeStatus.RUNNABLE) stopAutomaticExecution();
        });
    }
    /** Pausa Timeline y delega Pause de sesión sin alterar estados de procesos desde JavaFX. */
    @FXML private void handlePause() {
        automaticTimeline.pause();
        command(orchestrator::pause);
    }
    /** Delega Resume y reactiva Timeline sólo si Automatic seguía seleccionado y el runtime es ejecutable. */
    @FXML private void handleResume() {
        command(orchestrator::resume);
        if (automaticMode && runnable()) automaticTimeline.play();
    }
    /** Detiene Timeline, limpia selección/input y delega Reset antes de restaurar defaults visuales. */
    @FXML private void handleReset() {
        stopAutomaticExecution();
        selectedProgramPath = null; selectedProcessId = null;
        programPathField.clear(); keyboardField.clear(); feedback = "";
        orchestrator.reset(); restoreConfigurationDefaults(); refresh();
    }
    /**
     * Valida texto entero de entrada y lo envía al FIFO del Orchestrator, sin seleccionar PID ni bloquear
     * el hilo.
     */
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
    /** Comando de UI que admite errores de importación para tratarlos en una única frontera visual. */
    @FunctionalInterface
    private interface UiCommand { /** Ejecuta la acción de UI y permite propagar ProgramImportException a la frontera de errores del Controller. */ void run() throws ProgramImportException; }

    /**
     * Ejecuta un comando, muestra errores controlados y siempre refresca; ante fallo de ejecución detiene
     * Automatic.
     */
    private void command(UiCommand operation) {
        feedback = "";
        try { operation.run(); }
        catch (ProgramImportException | StorageException | IllegalArgumentException | IllegalStateException failure) {
            feedback = failure.getMessage(); showError(feedback);
        } catch (ExecutionEngineException failure) {
            stopAutomaticExecution(); feedback = failure.getMessage(); showError(feedback);
        } finally { refresh(); }
    }
    /** Detiene Timeline y desactiva el modo automático sin modificar el modelo. */
    private void stopAutomaticExecution() { automaticTimeline.stop(); automaticMode = false; }
    /**
     * Obtiene un único snapshot, informa la primera transición a ERROR sin diagnóstico previo y delega
     * renderizado.
     */
    private void refresh() {
        var snapshot = orchestrator.snapshot();
        if (snapshot.simulatorState() == SimulatorState.ERROR
                && (view == null || view.simulatorState() != SimulatorState.ERROR) && feedback.isBlank()) {
            feedback = "Runtime state could not be validated. Inspect the session or Reset.";
            showError(feedback);
        }
        render(snapshot);
    }
    /**
     * Actualiza paneles desde el snapshot y conserva selecciones; detiene Timeline al terminar, fallar o
     * esperar recursos/input.
     */
    private void render(SimulatorSnapshot snapshot) {
        view = snapshot;
        if (snapshot.simulatorState() == SimulatorState.ERROR || snapshot.simulatorState() == SimulatorState.FINISHED
                || (snapshot.simulatorState() == SimulatorState.RUNNING && snapshot.runtimeStatus().orElse(null) != RuntimeStatus.RUNNABLE)) {
            stopAutomaticExecution();
        }
        cpuTicksLabel.setText(snapshot.cpuTicks().isPresent() ? Long.toString(snapshot.cpuTicks().getAsLong()) : "\u2014");
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
    /**
     * Reemplaza filas conservando selección por PID y evita que listeners intermedios borren esa
     * selección.
     */
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
    /** Muestra el detalle del PID seleccionado exclusivamente desde el read model actual. */
    private void renderSelectedProcess() {
        pcbDetailArea.setText(view.processes().stream().filter(p -> Objects.equals(selectedProcessId, p.processId()))
                .findFirst().map(DashboardDetails::process).orElse("Select a process"));
    }
    /** Muestra registros del CPU activo y tooltips, sin confundirlos con el contexto guardado del PCB. */
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
    /**
     * Describe el estado observable y el modo de pacing para la GUI, sin determinar disponibilidad de
     * negocio.
     */
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
    /** Habilita controles según lifecycle y runtime del snapshot; no realiza transiciones de dominio. */
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
    /** Redondea el valor visual a la capacidad entera que se enviará a Application. */
    private static int sliderValue(Slider slider) {
        return (int) Math.round(slider.getValue());
    }

    /** Normaliza también el arrastre y mantiene el valor mostrado igual al enviado. */
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

    /** Ajusta máximo a capacidad menos uno y limita el valor dependiente al nuevo rango visual. */
    private static void updateDependentRange(Slider dependent, Slider capacity) {
        dependent.setMax(sliderValue(capacity) - 1);
        dependent.setValue(Math.min(sliderValue(dependent), dependent.getMax()));
    }

    /** Restituye los defaults del modelo en los cuatro sliders y recalcula límites dependientes. */
    private void restoreConfigurationDefaults() {
        var defaults = SimulatorConfiguration.defaults();
        mainMemorySlider.setValue(defaults.mainMemory().totalPositions());
        secondaryStorageSlider.setValue(defaults.secondaryStoragePositions());
        updateDependentRange(kernelMemorySlider, mainMemorySlider);
        updateDependentRange(virtualMemorySlider, secondaryStorageSlider);
        kernelMemorySlider.setValue(defaults.mainMemory().kernelReservedPositions());
        virtualMemorySlider.setValue(defaults.virtualMemoryPositions());
    }

    /** Presenta un Alert con el diagnóstico controlado, sin mostrar stack traces al usuario. */
    private void showError(String message) {
        var alert = new Alert(Alert.AlertType.ERROR);
        if (simulatorStateLabel.getScene() != null) alert.initOwner(simulatorStateLabel.getScene().getWindow());
        alert.setTitle("Operating System Simulator"); alert.setHeaderText(null); alert.setContentText(message); alert.show();
    }
}
