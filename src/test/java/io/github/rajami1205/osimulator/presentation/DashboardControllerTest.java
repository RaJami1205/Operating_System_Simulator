package io.github.rajami1205.osimulator.presentation;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.program.exception.ProgramImportException;
import io.github.rajami1205.osimulator.application.simulator.*;
import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot.*;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.storage.StorageRegion;
import java.nio.file.Path;
import java.time.*;
import io.github.rajami1205.osimulator.testing.ControlledClock;
import java.util.*;
import java.util.concurrent.*;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.Window;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DashboardControllerTest {
    @BeforeAll static void toolkit() {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("linux") || System.getenv("DISPLAY") != null);
        try { Platform.startup(() -> Platform.setImplicitExit(false)); } catch (IllegalStateException alreadyStarted) { }
    }
    @FunctionalInterface interface FxTest { void run() throws Exception; }
    private static void onFx(FxTest test) throws Exception {
        var task = new FutureTask<Void>(() -> {
            try { test.run(); } finally { for (var window : List.copyOf(Window.getWindows())) window.hide(); }
            return null;
        });
        Platform.runLater(task); task.get(30, TimeUnit.SECONDS);
    }
    private static Object get(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void call(Object object, String name) throws Exception {
        var method = object.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(object);
    }
    private static final class Fixture {
        final ControlledClock realClock = new ControlledClock();
        final SimulatorOrchestrator simulator = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), realClock);
        final Map<String, List<Instruction>> programs = new HashMap<>();
        final SimulatorController controller = new SimulatorController(simulator, path -> {
            if (path.getFileName().toString().equals("broken.asm")) throw new ProgramImportException("Invalid ASM", null);
            return programs.getOrDefault(path.getFileName().toString(), List.of());
        });
        final BorderPane root;
        Fixture() throws Exception {
            var loader = new FXMLLoader(SimulatorApplication.class.getResource("/io/github/rajami1205/osimulator/presentation/SimulatorView.fxml"));
            loader.setController(controller); root = loader.load(); new Scene(root, 1280, 720);
        }
        void action(String method) throws Exception { call(controller, method); }
        <T> T widget(String name, Class<T> type) throws Exception { return type.cast(get(controller, name)); }
        void load(String path, String... lines) throws Exception {
            Path selected = Path.of(path); programs.put(selected.getFileName().toString(), new AsmParser().parse(List.of(lines)));
            set(controller, "selectedProgramPath", selected); widget("programPathField", TextField.class).setText(path);
            action("refresh"); action("handleLoadProgram");
        }
        void enabled(String name, boolean expected) throws Exception { assertEquals(expected, !widget(name, Control.class).isDisabled(), name); }
        void closeAlerts() { for (var window : List.copyOf(Window.getWindows())) window.hide(); }
    }
    @Test void fxmlInjectionHandlersAndRepeatedBasenameLoading() throws Exception { onFx(() -> {
        var f = new Fixture();
        for (var field : SimulatorController.class.getDeclaredFields()) if (field.isAnnotationPresent(FXML.class)) {
            field.setAccessible(true); var value = field.get(f.controller); assertNotNull(value, field.getName());
            if (value instanceof Button button) assertNotNull(button.getOnAction(), field.getName());
        }
        assertNotNull(f.widget("keyboardField", TextField.class).getOnAction());
        f.enabled("initializeButton", true); f.enabled("browseProgramButton", false); f.enabled("keyboardSendButton", false);
        f.action("handleInitialize"); f.enabled("browseProgramButton", true); f.enabled("loadProgramButton", false);
        f.load("folder/one.asm", "INC"); f.load("other/two.asm", "ADD AX");
        assertEquals(List.of("one.asm", "two.asm"), f.simulator.snapshot().jobs().stream().map(j -> j.programName()).toList());
        f.load("different/one.asm", "INC"); assertEquals(2, f.simulator.snapshot().jobs().size());
        assertFalse(f.widget("statusLabel", Label.class).getText().isBlank()); f.closeAlerts();
        f.load("empty.asm"); assertEquals(2, f.simulator.snapshot().jobs().size()); f.closeAlerts();
        f.load("broken.asm"); assertTrue(f.widget("statusLabel", Label.class).getText().contains("Invalid ASM")); f.closeAlerts();
        f.enabled("startButton", true); f.action("handleStart"); f.enabled("loadProgramButton", false); f.enabled("browseProgramButton", false);
        f.enabled("stepButton", true); f.enabled("pauseButton", true); f.enabled("resumeButton", false);
        f.action("handlePause"); f.enabled("stepButton", false); f.enabled("resumeButton", true); f.enabled("keyboardSendButton", true);
        f.action("handleResume"); f.action("handleAutomatic"); f.enabled("stepButton", false); f.enabled("automaticButton", false);
        f.action("handleReset");
        assertEquals(256, f.widget("mainMemorySlider", Slider.class).getValue());
        assertEquals(32, f.widget("kernelMemorySlider", Slider.class).getValue());
        assertEquals(512, f.widget("secondaryStorageSlider", Slider.class).getValue());
        assertEquals(64, f.widget("virtualMemorySlider", Slider.class).getValue());
        assertTrue(f.widget("programPathField", TextField.class).getText().isEmpty());
    }); }
    @Test void keyboardValidationPausedCompletionAndScreenReplacement() throws Exception { onFx(() -> {
        var f = new Fixture(); f.action("handleInitialize");
        var input = f.widget("keyboardField", TextField.class);
        for (String invalid : List.of("", "text", "256", "-1", "99999999999")) {
            input.setText(invalid); f.action("handleKeyboardSend"); assertEquals(invalid, input.getText()); f.closeAlerts();
        }
        input.setText("255"); f.action("handleKeyboardSend"); assertEquals("", input.getText());
        f.load("io.asm", "INT 09H", "INT 10H", "INT 09H", "INC"); f.action("handleStart");
        for (int i = 0; i < 6; i++) f.action("handleStep");
        assertEquals(List.of("255"), f.widget("screenList", ListView.class).getItems());
        f.action("refresh"); f.action("refresh"); assertEquals(List.of("255"), f.widget("screenList", ListView.class).getItems());
        assertEquals(Optional.of(RuntimeStatus.WAITING_FOR_INPUT), f.simulator.snapshot().runtimeStatus());
        f.enabled("stepButton", false); f.enabled("automaticButton", false); f.enabled("pauseButton", true);
        f.action("handlePause"); input.setText("0"); f.action("handleKeyboardSend");
        assertEquals(1, f.simulator.snapshot().pendingKeyboardRequests().size()); assertEquals(SimulatorState.PAUSED, f.simulator.snapshot().simulatorState());
        f.action("handleResume"); f.action("handleStep");
        assertEquals(SimulatorState.FINISHED, f.simulator.snapshot().simulatorState());
        for (String name : List.of("initializeButton", "browseProgramButton", "loadProgramButton", "startButton", "stepButton", "automaticButton", "pauseButton", "resumeButton", "keyboardSendButton")) f.enabled(name, false);
        f.enabled("resetButton", true); assertEquals("Idle", f.widget("ownerValueLabel", Label.class).getText());
        assertEquals("—", f.widget("currentInstructionLabel", Label.class).getText());
        f.action("handleReset");
        for (String name : List.of("jobsTable", "processTable", "memoryTable", "storageTable", "completedTable", "programTable")) assertTrue(f.widget(name, TableView.class).getItems().isEmpty());
        for (String name : List.of("readyList", "suspendedList", "pendingList", "screenList")) assertTrue(f.widget(name, ListView.class).getItems().isEmpty());
    }); }
    @Test void selectionFollowsPidThroughRefreshSuspensionAndCompletion() throws Exception { onFx(() -> {
        var f = new Fixture(); f.action("handleInitialize"); f.load("one.asm", "INC"); f.load("two.asm", "INC");
        f.simulator.attemptNextAdmission(); f.simulator.attemptNextAdmission(); f.action("refresh");
        TableView<?> table = f.widget("processTable", TableView.class); table.getSelectionModel().select(0);
        assertEquals(1, get(f.controller, "selectedProcessId")); f.action("refresh");
        assertEquals(1, get(f.controller, "selectedProcessId"));
        f.simulator.swapOut(1); f.action("refresh");
        assertEquals(1, get(f.controller, "selectedProcessId"));
        assertTrue(f.widget("pcbDetailArea", TextArea.class).getText().contains("SUSPENDED"));
        f.action("handleStart"); f.action("handleStep");
        assertEquals(1, get(f.controller, "selectedProcessId"));
        f.action("handleStep"); assertNull(get(f.controller, "selectedProcessId"));
        assertEquals("Select a process", f.widget("pcbDetailArea", TextArea.class).getText());
        f.action("handleReset"); assertNull(get(f.controller, "selectedProcessId"));
    }); }
    @Test void matrixIncludesCapacityAndSafeErrorAndRowStylesAreRecycled() throws Exception { onFx(() -> {
        var f = new Fixture(); f.widget("mainMemorySlider", Slider.class).setValue(128); f.widget("kernelMemorySlider", Slider.class).setValue(127);
        f.action("handleInitialize"); f.load("large.asm", "INC", "INC"); f.action("handleStart");
        assertEquals(Optional.of(RuntimeStatus.WAITING_FOR_CAPACITY), f.simulator.snapshot().runtimeStatus());
        f.enabled("stepButton", false); f.enabled("pauseButton", true); f.enabled("keyboardSendButton", true);
        var queue = get(f.simulator, "readyQueue"); queue.getClass().getMethod("enqueue", int.class).invoke(queue, 999);
        f.action("refresh"); assertEquals(SimulatorState.ERROR, f.simulator.snapshot().simulatorState());
        f.closeAlerts(); f.action("refresh"); assertTrue(Window.getWindows().isEmpty());
        for (String name : List.of("initializeButton", "browseProgramButton", "loadProgramButton", "startButton", "stepButton", "automaticButton", "pauseButton", "resumeButton", "keyboardSendButton")) f.enabled(name, false);
        f.enabled("resetButton", true);
        @SuppressWarnings("unchecked") TableView<MemoryEntry> memory = (TableView<MemoryEntry>) get(f.controller, "memoryTable");
        var row = memory.getRowFactory().call(memory);
        updateRow(row, new MemoryEntry(0, "KERNEL", Optional.empty()), false); assertTrue(row.getStyleClass().contains("kernel-row"));
        updateRow(row, new MemoryEntry(32, "USER", Optional.empty()), false); assertTrue(row.getStyleClass().contains("user-row")); assertFalse(row.getStyleClass().contains("kernel-row"));
        updateRow(row, null, true); assertFalse(row.getStyleClass().contains("user-row"));
        @SuppressWarnings("unchecked") TableView<StorageEntry> storage = (TableView<StorageEntry>) get(f.controller, "storageTable");
        var storageRow = storage.getRowFactory().call(storage);
        String[] styles = {"file-index-row", "program-data-row", "swap-row"}; int i = 0;
        for (StorageRegion region : StorageRegion.values()) { updateRow(storageRow, new StorageEntry(0, region, "—"), false); assertTrue(storageRow.getStyleClass().contains(styles[i++])); }
        updateRow(storageRow, null, true); for (String style : styles) assertFalse(storageRow.getStyleClass().contains(style));
    }); }
    private static void updateRow(TableRow<?> row, Object value, boolean empty) throws Exception {
        var method = row.getClass().getDeclaredMethod("updateItem", Object.class, boolean.class); method.setAccessible(true); method.invoke(row, value, empty);
    }
    @Test void cpuTicksAndAccountingRenderAndAutomaticCountsOnce() throws Exception { onFx(() -> {
        var f = new Fixture();
        assertEquals("—", f.widget("cpuTicksLabel", Label.class).getText());
        String initial = DashboardDetails.accounting(io.github.rajami1205.osimulator.model.process.ProcessAccounting.initial());
        assertTrue(initial.contains("CPU ID: —")); assertTrue(initial.contains("Start Time: —"));
        assertTrue(initial.contains("CPU Ticks: 0")); assertTrue(initial.contains("Finish Time: —"));
        f.action("handleInitialize"); assertEquals("0", f.widget("cpuTicksLabel", Label.class).getText());
        f.load("ticks.asm", "ADD AX"); f.action("handleStart"); f.action("handleAutomatic");
        var timeline = (Timeline)get(f.controller, "automaticTimeline");
        try {
            assertEquals(1000, timeline.getKeyFrames().getFirst().getTime().toMillis());
            var callback = timeline.getKeyFrames().getFirst().getOnFinished();
            for(int tick=1;tick<=3;tick++) {
                if (tick > 1) f.realClock.advance(java.time.Duration.ofSeconds(1));
                callback.handle(new javafx.event.ActionEvent());
                assertEquals(tick, f.simulator.snapshot().cpuTicks().orElseThrow());
                assertEquals(Integer.toString(tick), f.widget("cpuTicksLabel", Label.class).getText());
                f.action("refresh"); assertEquals(tick, f.simulator.snapshot().cpuTicks().orElseThrow());
            }
            var completed = f.simulator.snapshot().completedProcesses().getFirst();
            @SuppressWarnings("unchecked") TableColumn<CompletedProcess,String> column = (TableColumn<CompletedProcess,String>) get(f.controller,"completedCpuTicksColumn");
            assertEquals("3", column.getCellObservableValue(completed).getValue());
            var detail = DashboardDetails.accounting(completed.accounting());
            assertEquals(Instant.parse("2026-10-01T12:00:00Z"), completed.accounting().startTime().orElseThrow());
            assertEquals(Instant.parse("2026-10-01T12:00:02Z"), completed.accounting().finishTime().orElseThrow());
            assertTrue(detail.contains("Elapsed Time: 00:00:02"));
            @SuppressWarnings("unchecked") TableView<CompletedProcess> table = (TableView<CompletedProcess>) get(f.controller, "completedTable");
            table.getSelectionModel().selectFirst();
            assertTrue(f.widget("completedDetailArea", TextArea.class).getText().contains("Elapsed Time: 00:00:02"));
            String fxml = new String(SimulatorController.class.getResourceAsStream("SimulatorView.fxml").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(fxml.contains("CPU TICKS")); assertFalse(fxml.contains("CPU CLOCK"));
            f.action("handleReset"); assertEquals("—", f.widget("cpuTicksLabel",Label.class).getText());
        } finally { timeline.stop(); }
    }); }
    @Test void layoutAtBothSupportedSizesAndLongValues() throws Exception { onFx(() -> {
        var f = new Fixture(); f.action("handleInitialize");
        f.load("example.asm", "MOV DX, \"a-long-program-name-with-readable-text.txt\"", "MOV AL, \"A long text value that must not widen the CPU pane\"", "ADD AX");
        f.load("input.asm", "INT 09H", "INC"); f.action("handleStart"); f.action("handleStep"); f.action("handleStep");
        for (int[] size : List.of(new int[]{1100,650}, new int[]{1280,720})) {
            f.root.resize(size[0], size[1]); f.root.applyCss(); f.root.layout();
            assertEquals(size[0], f.root.getWidth()); assertEquals(size[1], f.root.getHeight());
            assertTrue(f.widget("jobsTable", TableView.class).getWidth() > 200);
            assertTrue(f.widget("memoryTable", TableView.class).getHeight() > 100);
            assertTrue(f.widget("readyList", ListView.class).getHeight() >= 74);
            var image = f.root.snapshot(null, null);
            var pixels = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y=0; y<pixels.getHeight(); y++) for (int x=0; x<pixels.getWidth(); x++) pixels.setRGB(x,y,image.getPixelReader().getArgb(x,y));
            javax.imageio.ImageIO.write(pixels,"png",Path.of("target","f18-"+size[0]+"x"+size[1]+".png").toFile());
        }
        assertTrue(f.widget("dxValueLabel", Label.class).getTooltip().getText().contains("long-program"));
        assertTrue(f.widget("alValueLabel", Label.class).getTooltip().getText().contains("long text"));
    }); }
    @Test void readabilityAtThreeResolutionsAcrossExistingTabs() throws Exception { onFx(() -> {
        var f = new Fixture(); f.action("handleInitialize");
        f.load("done.asm", "INC");
        f.load("long-program-name.asm", "MOV DX, \"long-file-name-for-visible-tooltip.txt\"", "MOV AL, \"long text with preserved content\"", "ADD AX");
        f.load("input.asm", "INT 09H", "INC");
        f.action("handleStart"); f.action("handleStep"); f.action("handleStep"); f.action("handleStep");
        f.root.applyCss(); f.root.layout();
        var panes = f.root.lookupAll(".tab-pane").stream().map(TabPane.class::cast).toList();
        var upper = panes.stream().filter(pane -> pane.getTabs().stream().anyMatch(tab -> tab.getText().equals("Processes"))).findFirst().orElseThrow();
        var lower = panes.stream().filter(pane -> pane.getTabs().stream().anyMatch(tab -> tab.getText().equals("Completed"))).findFirst().orElseThrow();
        assertEquals(List.of("Workload", "Processes"), upper.getTabs().stream().map(Tab::getText).toList());
        assertEquals(List.of("Main Memory", "Secondary Storage", "Active Program", "I/O", "Completed"), lower.getTabs().stream().map(Tab::getText).toList());
        for (int[] size : List.of(new int[]{1100,650},new int[]{1280,720},new int[]{1920,1080})) {
            f.root.resize(size[0],size[1]);
            for (String lifecycle : List.of("CONFIGURING","INITIALIZED","PROGRAM_LOADED","RUNNING","PAUSED","FINISHED","ERROR")) {
                f.widget("simulatorStateLabel",Label.class).setText(lifecycle);
                for (String runtime : List.of("RUNNABLE","WAITING_FOR_INPUT","WAITING_FOR_CAPACITY")) {
                    f.widget("runtimeStatusLabel",Label.class).setText(runtime);
                    f.widget("cpuTicksLabel",Label.class).setText("1234567890");
                    f.root.applyCss(); f.root.layout();
                    for(String id : List.of("simulatorStateLabel","runtimeStatusLabel","cpuTicksLabel","ownerValueLabel")) {
                        var label=f.widget(id,Label.class);
                        var text=new javafx.scene.text.Text(label.getText()); text.setFont(label.getFont());
                        assertTrue(label.getWidth()+1>=text.getLayoutBounds().getWidth(),id+" clipped at "+size[0]);
                        assertTrue(label.localToScene(label.getBoundsInLocal()).getMaxX()<=size[0],id);
                    }
                }
            }
            f.action("refresh");
            for(var tab:upper.getTabs()) {
                upper.getSelectionModel().select(tab); f.root.applyCss(); f.root.layout();
                if(tab.getText().equals("Processes")) f.widget("processTable",TableView.class).getSelectionModel().selectFirst();
                saveReadabilityImage(f.root,size,"upper-"+tab.getText());
            }
            for(var tab:lower.getTabs()) {
                lower.getSelectionModel().select(tab); f.root.applyCss(); f.root.layout();
                if(tab.getText().equals("I/O")) {
                    @SuppressWarnings("unchecked") var output=(ListView<String>)get(f.controller,"screenList");
                    output.getItems().setAll("17","42","255");
                    f.root.applyCss(); f.root.layout();
                    var cell=(ListCell<?>)output.lookup(".list-cell");
                    assertEquals(14,cell.getFont().getSize());
                }
                if(tab.getText().equals("Completed")) {
                    f.widget("completedTable",TableView.class).getSelectionModel().selectFirst();
                    var detail=f.widget("completedDetailArea",TextArea.class);
                    assertEquals(13,detail.getFont().getSize()); assertTrue(detail.isWrapText());
                    assertTrue(detail.getText().contains("Start Time:")); assertTrue(detail.getText().contains("Elapsed Time:"));
                }
                saveReadabilityImage(f.root,size,"lower-"+tab.getText().replace(" ","-").replace("/","-"));
            }
            for(String id:List.of("jobsTable","processTable","memoryTable","storageTable","programTable","completedTable")) {
                assertEquals(29,f.widget(id,TableView.class).getFixedCellSize());
            }
            assertEquals(15,f.widget("currentInstructionLabel",Label.class).getFont().getSize());
            assertEquals(13,f.widget("pcbDetailArea",TextArea.class).getFont().getSize());
            var scroll=(ScrollPane)f.root.lookup(".configuration-scroll");
            scroll.setVvalue(1); f.root.layout();
            var reset=f.widget("resetButton",Button.class).localToScene(f.widget("resetButton",Button.class).getBoundsInLocal());
            var viewport=scroll.lookup(".viewport").localToScene(scroll.lookup(".viewport").getBoundsInLocal());
            assertTrue(reset.getMaxY()<=viewport.getMaxY()+1,"Reset must be reachable by scrolling");
            scroll.setVvalue(0);
        }
    }); }
    private static void saveReadabilityImage(BorderPane root,int[] size,String view) throws Exception {
        root.applyCss(); root.layout();
        var image=root.snapshot(null,null);
        var pixels=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<pixels.getHeight();y++) for(int x=0;x<pixels.getWidth();x++) pixels.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        javax.imageio.ImageIO.write(pixels,"png",Path.of("target","f19-readability-"+size[0]+"x"+size[1]+"-"+view+".png").toFile());
    }

}
