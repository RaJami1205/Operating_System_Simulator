package io.github.rajami1205.osimulator.presentation;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.simulator.SimulatorOrchestrator;
import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import javafx.application.Platform;
import javafx.animation.Animation;
import javafx.animation.Timeline;
import javafx.fxml.FXMLLoader;
import javafx.scene.control.Button;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BasicIoControllerTest {
    @Test void automaticStopsOnWaitWithoutPausingAndDoesNotRestartAfterInput() throws Exception {
        // JavaFX requires a display; domain/application tests remain headless and unconditional.
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("linux")
                || System.getenv("DISPLAY") != null, "JavaFX display unavailable");
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { /* Existing JavaFX test runtime. */ }
        var task=new FutureTask<Void>(() -> {
            var simulator=new SimulatorOrchestrator(new ProgramLoader(),new ExecutionEngine());
            var controller=new SimulatorController(simulator,path -> { throw new AssertionError("Import unused"); });
            var loader=new FXMLLoader(SimulatorApplication.class.getResource("/io/github/rajami1205/osimulator/presentation/SimulatorView.fxml"));
            loader.setController(controller); assertNotNull(loader.load());
            simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(128,32),512,64));
            simulator.loadProgram(new AsmParser().parse(List.of("INT 09H","INC"))); simulator.start();
            var timeline=(Timeline)field(controller,"automaticTimeline");
            try {
                invoke(controller,"handleAutomatic");
                assertEquals(Animation.Status.RUNNING,timeline.getStatus());
                // Drive actual callbacks deterministically on the FX thread, without sleeping for pacing.
                var callback=timeline.getKeyFrames().getFirst().getOnFinished();
                callback.handle(new javafx.event.ActionEvent());
                assertFalse(simulator.waitingForInput());
                callback.handle(new javafx.event.ActionEvent());
                assertTrue(simulator.waitingForInput());
                assertEquals(Animation.Status.STOPPED,timeline.getStatus());
                assertEquals(false,field(controller,"automaticMode"));
                assertEquals(SimulatorState.RUNNING,simulator.snapshot().simulatorState());
                assertTrue(((Button)field(controller,"stepButton")).isDisabled());
                assertTrue(((Button)field(controller,"automaticButton")).isDisabled());
                var waiting=simulator.snapshot();
                callback.handle(new javafx.event.ActionEvent());
                invoke(controller,"handleStep"); invoke(controller,"handleAutomatic");
                assertEquals(waiting,simulator.snapshot());
                assertEquals(Animation.Status.STOPPED,timeline.getStatus());
                invoke(controller,"handlePause"); simulator.submitKeyboardInput(55);
                assertEquals(SimulatorState.PAUSED,simulator.snapshot().simulatorState());
                invoke(controller,"handleResume");
                assertFalse(simulator.waitingForInput());
                assertEquals(Animation.Status.STOPPED,timeline.getStatus());
                assertEquals(false,field(controller,"automaticMode"));
                assertFalse(((Button)field(controller,"stepButton")).isDisabled());
                assertEquals(55,simulator.snapshot().cpu().orElseThrow().dx());
                invoke(controller,"handleReset");
                simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(128,32),512,64));
                simulator.loadProgram(new AsmParser().parse(List.of("MOV DX, \"notes.txt\"","MOV DX, 7")));
                simulator.start();
                invoke(controller,"handleStep");
                assertEquals("notes.txt",((javafx.scene.control.Label)field(controller,"dxValueLabel")).getText());
                invoke(controller,"handleStep");
                assertEquals("7",((javafx.scene.control.Label)field(controller,"dxValueLabel")).getText());

            } finally { timeline.stop(); }
            return null;
        });
        Platform.runLater(task); task.get(20,TimeUnit.SECONDS);
    }
    private static Object field(Object target,String name) throws Exception {
        var field=target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void invoke(Object target,String name) throws Exception {
        var method=target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target);
    }
}
