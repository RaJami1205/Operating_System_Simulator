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
    private void initialize() { simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140),512,64)); }
    private void admit(int id,int size) {
        simulator.submitProgram(new ProgramImage("p"+id,Collections.nCopies(size,new IncInstruction())));
        assertEquals(Optional.of(new AdmissionResult.Admitted(id)),simulator.attemptNextAdmission());
    }
    @Test void explicitTransfersPreserveLifecycleAndResetDiscardsWholeSession() {
        assertThrows(IllegalStateException.class,()->simulator.swapOut(1));initialize();
        admit(1,40);admit(2,40);
        assertEquals(new SwapResult.Completed(),simulator.swapOut(1));
        assertEquals(new SwapResult.Waiting(SwapResult.Reason.INSUFFICIENT_SWAP),simulator.swapOut(2));
        assertEquals(SimulatorState.PROGRAM_LOADED,simulator.snapshot().simulatorState());
        assertEquals(Optional.of(2),simulator.selectNextReadyProcess());
        simulator.swapIn(1);assertEquals(Optional.of(2),simulator.selectNextReadyProcess());
        simulator.swapOut(1);simulator.reset();
        assertEquals(SimulatorState.CONFIGURING,simulator.snapshot().simulatorState());
        assertThrows(IllegalStateException.class,()->simulator.swapIn(1));
        initialize();assertTrue(simulator.selectNextReadyProcess().isEmpty());
        assertThrows(IllegalArgumentException.class,()->simulator.swapIn(1));
        admit(1,64);assertEquals(new SwapResult.Completed(),simulator.swapOut(1));simulator.swapIn(1);
    }
    @Test void activeOwnerCannotSwapButReadyProcessCan() {
        initialize(); simulator.loadProgram(new AsmParser().parse(List.of("INC","INC")));
        simulator.loadProgram(new AsmParser().parse(List.of("MOV DX, 25","INC")));
        assertThrows(IllegalArgumentException.class,()->simulator.swapOut(1));
        simulator.start(); simulator.step();
        assertThrows(IllegalStateException.class,()->simulator.swapOut(1));
        simulator.pause(); assertThrows(IllegalStateException.class,()->simulator.swapOut(1));
        assertEquals(new SwapResult.Completed(),simulator.swapOut(2));
        simulator.resume(); simulator.step();
        assertEquals(List.of(2),simulator.snapshot().readyQueue());
        simulator.step(); simulator.step();
        assertEquals(SimulatorState.FINISHED,simulator.snapshot().simulatorState());
        assertEquals(25,simulator.completedProcesses().getLast().finalContext().dx());
        assertEquals(4,simulator.snapshot().cpuTicks().orElseThrow());
    }
}
