package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.instruction.IncInstruction;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SwappingOrchestratorTest {
    private final SimulatorOrchestrator simulator=new SimulatorOrchestrator(new ProgramLoader(),new ExecutionEngine());
    private void initialize() { simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(128,32),512,64)); }
    private void admit(int id,int size) {
        simulator.submitProgram(new ProgramImage("p"+id,Collections.nCopies(size,new IncInstruction())));
        assertEquals(Optional.of(new AdmissionResult.Admitted(id)),simulator.attemptNextAdmission());
    }
    @Test void explicitTransfersPreserveLifecycleAndResetDiscardsWholeSession() {
        assertThrows(IllegalStateException.class,()->simulator.swapOut(1));initialize();
        admit(1,40);admit(2,40);
        assertEquals(new SwapResult.Completed(),simulator.swapOut(1));
        assertEquals(new SwapResult.Waiting(SwapResult.Reason.INSUFFICIENT_SWAP),simulator.swapOut(2));
        assertEquals(SimulatorState.INITIALIZED,simulator.snapshot().simulatorState());
        assertEquals(Optional.of(2),simulator.selectNextReadyProcess());
        simulator.swapIn(1);assertEquals(Optional.of(2),simulator.selectNextReadyProcess());
        simulator.swapOut(1);simulator.reset();
        assertEquals(SimulatorState.CONFIGURING,simulator.snapshot().simulatorState());
        assertThrows(IllegalStateException.class,()->simulator.swapIn(1));
        initialize();assertTrue(simulator.selectNextReadyProcess().isEmpty());
        assertThrows(IllegalArgumentException.class,()->simulator.swapIn(1));
        admit(1,64);assertEquals(new SwapResult.Completed(),simulator.swapOut(1));simulator.swapIn(1);
    }
    @Test void legacyReadyRunningPausedAndKeyboardWaitAreNeverSwapped() {
        initialize();simulator.loadProgram(new AsmParser().parse(List.of("INT 09H","INC")));
        assertThrows(IllegalStateException.class,()->simulator.swapOut(1));simulator.start();simulator.step();
        assertThrows(IllegalStateException.class,()->simulator.swapOut(1));
        simulator.step();assertTrue(simulator.waitingForInput());var before=simulator.snapshot();
        assertThrows(IllegalStateException.class,()->simulator.swapOut(1));
        assertThrows(IllegalStateException.class,()->simulator.swapIn(1));
        assertThrows(IllegalStateException.class,()->simulator.swapOut(2));
        assertEquals(before,simulator.snapshot());simulator.pause();
        assertThrows(IllegalStateException.class,()->simulator.swapOut(1));
        simulator.submitKeyboardInput(25);simulator.resume();
        assertFalse(simulator.waitingForInput());assertEquals(25,simulator.snapshot().cpu().orElseThrow().dx());
        simulator.step();assertEquals(SimulatorState.FINISHED,simulator.snapshot().simulatorState());
    }
}
