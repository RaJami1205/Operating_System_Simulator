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
    @Test void automaticExecutesScreenOutputAndStopsAtFinish() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("linux")
                || System.getenv("DISPLAY") != null, "JavaFX display unavailable");
        try { Platform.startup(() -> Platform.setImplicitExit(false)); }
        catch (IllegalStateException alreadyStarted) { }
        var task=new FutureTask<Void>(() -> {
            var simulator=new SimulatorOrchestrator(new ProgramLoader(),new ExecutionEngine());
            var controller=new SimulatorController(simulator,path -> { throw new AssertionError("Import unused"); });
            var loader=new FXMLLoader(SimulatorApplication.class.getResource("/io/github/rajami1205/osimulator/presentation/SimulatorView.fxml"));
            loader.setController(controller); assertNotNull(loader.load());
            simulator.initialize(SimulatorConfiguration.defaults());
            simulator.loadProgram(new AsmParser().parse(List.of("MOV DX, 55","INT 10H","INT 20H","INC")));
            simulator.start(); invoke(controller,"refresh"); invoke(controller,"handleAutomatic");
            var timeline=(Timeline)field(controller,"automaticTimeline");
            try {
                var callback=timeline.getKeyFrames().getFirst().getOnFinished();
                callback.handle(new javafx.event.ActionEvent());
                assertEquals(1,simulator.snapshot().cpuTicks().orElseThrow());
                assertTrue(simulator.screenOutput().isEmpty());
                invoke(controller,"handlePause"); var paused=simulator.snapshot();
                callback.handle(new javafx.event.ActionEvent()); assertEquals(paused,simulator.snapshot());
                invoke(controller,"handleResume"); callback.handle(new javafx.event.ActionEvent());
                assertEquals(List.of(55),simulator.screenOutput());
                callback.handle(new javafx.event.ActionEvent());
                assertEquals(SimulatorState.FINISHED,simulator.snapshot().simulatorState());
                assertEquals(Animation.Status.STOPPED,timeline.getStatus());
                assertEquals(3,simulator.snapshot().cpuTicks().orElseThrow());
                assertEquals(3,simulator.snapshot().simulationTimeSeconds().orElseThrow());
                var finished=simulator.snapshot(); callback.handle(new javafx.event.ActionEvent());
                assertEquals(finished,simulator.snapshot()); assertEquals(0,finished.cpu().orElseThrow().accumulator());
                invoke(controller,"handleReset"); assertTrue(simulator.screenOutput().isEmpty());
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
