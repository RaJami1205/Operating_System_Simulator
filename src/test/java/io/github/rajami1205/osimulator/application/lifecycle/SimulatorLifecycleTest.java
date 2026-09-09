package io.github.rajami1205.osimulator.application.lifecycle;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.rajami1205.osimulator.application.lifecycle.exception.InvalidSimulatorTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class SimulatorLifecycleTest {

    @Test
    void shouldStartInConfiguringState() {
        SimulatorLifecycle lifecycle = new SimulatorLifecycle();

        assertEquals(SimulatorState.CONFIGURING, lifecycle.state());
    }

    @Test
    void shouldCompleteNormalLifecycleFlow() {
        SimulatorLifecycle lifecycle = new SimulatorLifecycle();

        lifecycle.initialize();
        assertEquals(SimulatorState.INITIALIZED, lifecycle.state());

        lifecycle.markProgramLoaded();
        assertEquals(SimulatorState.PROGRAM_LOADED, lifecycle.state());

        lifecycle.startExecution();
        assertEquals(SimulatorState.RUNNING, lifecycle.state());

        lifecycle.finishExecution();
        assertEquals(SimulatorState.FINISHED, lifecycle.state());
    }

    @Test
    void shouldCompleteLifecycleFlowWithPauseAndResume() {
        SimulatorLifecycle lifecycle = lifecycleIn(SimulatorState.RUNNING);

        lifecycle.pauseExecution();
        assertEquals(SimulatorState.PAUSED, lifecycle.state());

        lifecycle.resumeExecution();
        assertEquals(SimulatorState.RUNNING, lifecycle.state());

        lifecycle.finishExecution();
        assertEquals(SimulatorState.FINISHED, lifecycle.state());
    }

    @Test
    void shouldSupportRepeatedPauseAndResumeCycles() {
        SimulatorLifecycle lifecycle = lifecycleIn(SimulatorState.RUNNING);

        lifecycle.pauseExecution();
        lifecycle.resumeExecution();
        lifecycle.pauseExecution();
        lifecycle.resumeExecution();

        assertEquals(SimulatorState.RUNNING, lifecycle.state());
    }

    @ParameterizedTest
    @EnumSource(
            value = SimulatorState.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = "CONFIGURING"
    )
    void shouldRejectInitializeOutsideConfiguring(SimulatorState initialState) {
        SimulatorLifecycle lifecycle = lifecycleIn(initialState);

        assertInvalidTransitionPreservesState(lifecycle, lifecycle::initialize);
    }

    @ParameterizedTest
    @EnumSource(
            value = SimulatorState.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = "INITIALIZED"
    )
    void shouldRejectMarkProgramLoadedOutsideInitialized(SimulatorState initialState) {
        SimulatorLifecycle lifecycle = lifecycleIn(initialState);

        assertInvalidTransitionPreservesState(lifecycle, lifecycle::markProgramLoaded);
    }

    @ParameterizedTest
    @EnumSource(
            value = SimulatorState.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = "PROGRAM_LOADED"
    )
    void shouldRejectStartExecutionOutsideProgramLoaded(SimulatorState initialState) {
        SimulatorLifecycle lifecycle = lifecycleIn(initialState);

        assertInvalidTransitionPreservesState(lifecycle, lifecycle::startExecution);
    }

    @ParameterizedTest
    @EnumSource(
            value = SimulatorState.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = "RUNNING"
    )
    void shouldRejectPauseExecutionOutsideRunning(SimulatorState initialState) {
        SimulatorLifecycle lifecycle = lifecycleIn(initialState);

        assertInvalidTransitionPreservesState(lifecycle, lifecycle::pauseExecution);
    }

    @ParameterizedTest
    @EnumSource(
            value = SimulatorState.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = "PAUSED"
    )
    void shouldRejectResumeExecutionOutsidePaused(SimulatorState initialState) {
        SimulatorLifecycle lifecycle = lifecycleIn(initialState);

        assertInvalidTransitionPreservesState(lifecycle, lifecycle::resumeExecution);
    }

    @ParameterizedTest
    @EnumSource(
            value = SimulatorState.class,
            mode = EnumSource.Mode.EXCLUDE,
            names = "RUNNING"
    )
    void shouldRejectFinishExecutionOutsideRunning(SimulatorState initialState) {
        SimulatorLifecycle lifecycle = lifecycleIn(initialState);

        assertInvalidTransitionPreservesState(lifecycle, lifecycle::finishExecution);
    }

    @ParameterizedTest
    @EnumSource(SimulatorState.class)
    void shouldMarkErrorFromEveryState(SimulatorState initialState) {
        SimulatorLifecycle lifecycle = lifecycleIn(initialState);

        lifecycle.markError();

        assertEquals(SimulatorState.ERROR, lifecycle.state());
    }

    @ParameterizedTest
    @EnumSource(SimulatorState.class)
    void shouldResetFromEveryState(SimulatorState initialState) {
        SimulatorLifecycle lifecycle = lifecycleIn(initialState);

        lifecycle.reset();

        assertEquals(SimulatorState.CONFIGURING, lifecycle.state());
    }

    @Test
    void shouldKeepMarkErrorIdempotent() {
        SimulatorLifecycle lifecycle = new SimulatorLifecycle();

        lifecycle.markError();
        lifecycle.markError();
        lifecycle.markError();

        assertEquals(SimulatorState.ERROR, lifecycle.state());
    }

    @Test
    void shouldKeepResetIdempotent() {
        SimulatorLifecycle lifecycle = new SimulatorLifecycle();

        lifecycle.reset();
        lifecycle.reset();
        lifecycle.reset();

        assertEquals(SimulatorState.CONFIGURING, lifecycle.state());
    }

    @Test
    void shouldRecoverAfterErrorThroughReset() {
        SimulatorLifecycle lifecycle = new SimulatorLifecycle();
        lifecycle.initialize();
        lifecycle.markError();

        lifecycle.reset();
        assertEquals(SimulatorState.CONFIGURING, lifecycle.state());

        lifecycle.initialize();
        assertEquals(SimulatorState.INITIALIZED, lifecycle.state());
    }

    @Test
    void shouldReuseLifecycleAfterFinishedThroughReset() {
        SimulatorLifecycle lifecycle = lifecycleIn(SimulatorState.FINISHED);

        lifecycle.reset();
        assertEquals(SimulatorState.CONFIGURING, lifecycle.state());

        lifecycle.initialize();
        assertEquals(SimulatorState.INITIALIZED, lifecycle.state());
    }

    @Test
    void shouldRejectDirectRestartAfterFinishedAndAllowReset() {
        SimulatorLifecycle lifecycle = lifecycleIn(SimulatorState.FINISHED);

        assertInvalidTransitionPreservesState(lifecycle, lifecycle::startExecution);

        lifecycle.reset();
        assertEquals(SimulatorState.CONFIGURING, lifecycle.state());
    }

    private static SimulatorLifecycle lifecycleIn(SimulatorState state) {
        SimulatorLifecycle lifecycle = new SimulatorLifecycle();

        switch (state) {
            case CONFIGURING -> {
            }
            case INITIALIZED -> lifecycle.initialize();
            case PROGRAM_LOADED -> {
                lifecycle.initialize();
                lifecycle.markProgramLoaded();
            }
            case RUNNING -> {
                lifecycle.initialize();
                lifecycle.markProgramLoaded();
                lifecycle.startExecution();
            }
            case PAUSED -> {
                lifecycle.initialize();
                lifecycle.markProgramLoaded();
                lifecycle.startExecution();
                lifecycle.pauseExecution();
            }
            case FINISHED -> {
                lifecycle.initialize();
                lifecycle.markProgramLoaded();
                lifecycle.startExecution();
                lifecycle.finishExecution();
            }
            case ERROR -> lifecycle.markError();
        }

        return lifecycle;
    }

    private static void assertInvalidTransitionPreservesState(
            SimulatorLifecycle lifecycle,
            Executable operation
    ) {
        SimulatorState stateBeforeOperation = lifecycle.state();

        InvalidSimulatorTransitionException exception = assertThrows(
                InvalidSimulatorTransitionException.class,
                operation
        );

        assertAll(
                () -> assertNotNull(exception.getMessage()),
                () -> assertFalse(exception.getMessage().isBlank()),
                () -> assertEquals(stateBeforeOperation, lifecycle.state())
        );
    }
}
