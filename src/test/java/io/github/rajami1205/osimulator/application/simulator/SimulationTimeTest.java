package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.execution.*;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.io.KeyboardDevice;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.testing.ControlledClock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SimulationTimeTest {
    private final ControlledClock realClock = new ControlledClock();
    private final SimulatorOrchestrator simulator = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), realClock);
    private void init(String... lines) {
        simulator.initialize(SimulatorConfiguration.defaults());
        simulator.submitProgram(new ProgramImage("p", new AsmParser().parse(List.of(lines))));
        simulator.start();
    }
    private long time() { return simulator.snapshot().simulationTimeSeconds().orElseThrow(); }
    private <T> T field(String name, Class<T> type) throws Exception {
        var field = SimulatorOrchestrator.class.getDeclaredField(name);
        field.setAccessible(true); return type.cast(field.get(simulator));
    }
    private ProcessControlBlock pcb() throws Exception { return field("processTable", ProcessTable.class).find(1).orElseThrow(); }

    @Test void snapshotRejectsInvalidTimeAndLegacyConstructorKeepsItAbsent() {
        var value = simulator.snapshot();
        assertThrows(NullPointerException.class, () -> withTime(value, null));
        assertThrows(IllegalArgumentException.class, () -> withTime(value, OptionalLong.of(-1)));
        assertEquals(Long.MAX_VALUE, withTime(value, OptionalLong.of(Long.MAX_VALUE)).simulationTimeSeconds().orElseThrow());
        var legacy = new SimulatorSnapshot(value.simulatorState(), value.cpu(), value.currentInstruction(),
                value.process(), value.program(), value.memory());
        assertTrue(legacy.simulationTimeSeconds().isEmpty());
    }

    private SimulatorSnapshot withTime(SimulatorSnapshot value, OptionalLong seconds) {
        return new SimulatorSnapshot(value.simulatorState(), value.cpu(), value.currentInstruction(), value.process(),
                value.program(), value.memory(), value.runtimeStatus(), value.ownerPid(), value.configuration(),
                value.jobs(), value.processes(), value.readyQueue(), value.suspendedReadyQueue(), value.pendingKeyboardRequests(),
                value.completedProcesses(), value.storage(), value.screenOutput(), value.cpuTicks(),
                value.nextInstructionPhysicalAddress(), value.addressTranslationError(), seconds);
    }

    @Test void uniformExecutionLifecycleAndHistoricalSnapshotsKeepIndependentUnits() {
        assertTrue(simulator.snapshot().simulationTimeSeconds().isEmpty());
        simulator.initialize(SimulatorConfiguration.defaults());
        assertEquals(0, time());
        simulator.submitProgram(new ProgramImage("p", new AsmParser().parse(List.of("ADD AX", "INC"))));
        simulator.start(); assertEquals(0, time());
        var initial = simulator.snapshot();
        var expected = List.of(TickResult.INSTRUCTION_COMPLETED, TickResult.PROGRAM_FINISHED);
        for (int i = 0; i < expected.size(); i++) {
            var result = assertInstanceOf(RuntimeStepResult.Executed.class, simulator.step());
            assertEquals(expected.get(i), result.tickResult()); assertEquals(i + 1, time());
            if (i == 0) {
                simulator.pause(); realClock.advance(Duration.ofHours(2));
                assertThrows(IllegalStateException.class, simulator::step);
                assertEquals(1, time()); simulator.resume(); assertEquals(1, time());
            }
        }
        assertEquals(2, simulator.snapshot().cpuTicks().orElseThrow());
        assertEquals(Duration.ofHours(2), simulator.completedProcesses().getFirst().accounting().elapsedTime().orElseThrow());
        assertEquals(0, initial.simulationTimeSeconds().orElseThrow());
        assertThrows(IllegalStateException.class, simulator::step); assertEquals(2, time());
        simulator.reset(); assertTrue(simulator.snapshot().simulationTimeSeconds().isEmpty());
        simulator.initialize(SimulatorConfiguration.defaults()); assertEquals(0, time());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void inputAtStepBoundaryIsFreeWhetherItFinishesOrEnablesExecution(boolean terminal) throws Exception {
        if (terminal) init("INT 09H"); else init("INT 09H", "INC");
        assertEquals(TickResult.WAITING_FOR_INPUT,
                assertInstanceOf(RuntimeStepResult.Executed.class, simulator.step()).tickResult());
        assertEquals(1, time());
        assertEquals(new RuntimeStepResult.Idle(RuntimeStatus.WAITING_FOR_INPUT), simulator.step());
        assertEquals(2, time()); assertEquals(1, simulator.snapshot().cpuTicks().orElseThrow());
        field("keyboard", KeyboardDevice.class).submit(42);
        var result = simulator.step();
        assertEquals(terminal ? 2 : 3, time());
        assertEquals(terminal ? 1 : 2, simulator.snapshot().cpuTicks().orElseThrow());
        assertEquals(RuntimeStatus.FINISHED, result.status());
        if (terminal) assertInstanceOf(RuntimeStepResult.Idle.class, result);
        else assertInstanceOf(RuntimeStepResult.Executed.class, result);
        assertEquals(new RuntimeStepResult.Idle(RuntimeStatus.FINISHED), field("runtime", MultiprocessRuntime.class).step());
        assertEquals(terminal ? 2 : 3, time());
        var seconds = SimulationClock.class.getDeclaredField("seconds");
        seconds.setAccessible(true); seconds.setLong(field("simulationClock", SimulationClock.class), Long.MAX_VALUE);
        assertEquals(new RuntimeStepResult.Idle(RuntimeStatus.FINISHED), field("runtime", MultiprocessRuntime.class).step());
        assertEquals(Long.MAX_VALUE, time());
    }

    @Test void externalInputCompletionAndResumeConsumeNoTime() {
        init("INT 09H"); simulator.step();
        simulator.pause(); simulator.submitKeyboardInput(7); assertEquals(1, time());
        simulator.resume(); assertEquals(SimulatorState.FINISHED, simulator.snapshot().simulatorState());
        assertEquals(1, time());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void overflowPreventsCpuOrIdleIntervalEffects(boolean idle) throws Exception {
        if (idle) { init("INT 09H"); simulator.step(); }
        else init("INC");
        var clock = field("simulationClock", SimulationClock.class);
        var seconds = SimulationClock.class.getDeclaredField("seconds");
        seconds.setAccessible(true); seconds.setLong(clock, Long.MAX_VALUE);
        var before = simulator.snapshot();
        assertThrows(ExecutionEngineException.class, simulator::step);
        var after = simulator.snapshot();
        assertEquals(Long.MAX_VALUE, time()); assertEquals(SimulatorState.ERROR, after.simulatorState());
        assertEquals(before.cpu(), after.cpu()); assertEquals(before.processes(), after.processes());
        assertEquals(before.memory(), after.memory()); assertEquals(before.cpuTicks(), after.cpuTicks());
    }

    @Test void errorsBeforeCommitDoNotAdvanceButCleanupFailurePreservesConfirmedTime() throws Exception {
        init("POP AX"); assertThrows(ExecutionEngineException.class, simulator::step); assertEquals(0, time());
        simulator.reset(); init("MOV AX, 32767", "LOAD AX", "ADD AX");
        for (int i = 0; i < 2; i++) simulator.step();
        assertThrows(ExecutionEngineException.class, simulator::step); assertEquals(2, time());
        simulator.reset(); init("INC"); pcb().setNextPcbAddress(Optional.of(new PcbAddress(9)));
        assertThrows(ExecutionEngineException.class, simulator::step);
        assertEquals(1, time()); assertEquals(1, simulator.snapshot().cpuTicks().orElseThrow());
        assertEquals(SimulatorState.ERROR, simulator.snapshot().simulatorState());
    }
}
