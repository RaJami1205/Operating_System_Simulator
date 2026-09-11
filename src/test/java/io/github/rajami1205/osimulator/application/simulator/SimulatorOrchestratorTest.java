package io.github.rajami1205.osimulator.application.simulator;

import static org.junit.jupiter.api.Assertions.*;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.program.exception.ProgramLoadException;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.instruction.AddInstruction;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.MovInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import io.github.rajami1205.osimulator.model.instruction.binary.InstructionBinaryCodec;
import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryConfigurationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SimulatorOrchestratorTest {
    private final SimulatorOrchestrator simulator = new SimulatorOrchestrator(
            new ProgramLoader(), new ExecutionEngine(), new InstructionBinaryCodec());

    @Test
    void requiresAllDependencies() {
        assertThrows(NullPointerException.class, () -> new SimulatorOrchestrator(
                null, new ExecutionEngine(), new InstructionBinaryCodec()));
        assertThrows(NullPointerException.class, () -> new SimulatorOrchestrator(
                new ProgramLoader(), null, new InstructionBinaryCodec()));
        assertThrows(NullPointerException.class, () -> new SimulatorOrchestrator(
                new ProgramLoader(), new ExecutionEngine(), null));
    }

    @Test
    void startsWithoutSessionAndInitializesRealCpuAndMemory() {
        assertEmptySession(simulator.snapshot());
        simulator.initialize(128, 16);
        var snapshot = simulator.snapshot();
        assertEquals(SimulatorState.INITIALIZED, snapshot.simulatorState());
        assertEquals(new SimulatorSnapshot.CpuSnapshot(0, 0, 0, 0, 0, 0, Optional.empty()),
                snapshot.cpu().orElseThrow());
        assertTrue(snapshot.process().isEmpty());
        assertTrue(snapshot.currentInstruction().isEmpty());
        assertTrue(snapshot.program().isEmpty());
        assertEquals(128, snapshot.memory().size());
        for (int address = 0; address < 128; address++) {
            var row = snapshot.memory().get(address);
            assertEquals(address, row.address());
            assertEquals(address < 16 ? "KERNEL" : "USER", row.region());
            assertTrue(row.content().isEmpty());
        }
    }

    @Test
    void invalidConfigurationIsRecoverable() {
        var before = simulator.snapshot();
        assertThrows(InvalidMemoryConfigurationException.class, () -> simulator.initialize(127, 16));
        assertEquals(before, simulator.snapshot());
        assertThrows(InvalidMemoryConfigurationException.class, () -> simulator.initialize(128, 0));
        assertEquals(before, simulator.snapshot());
        simulator.initialize(128, 16);
        assertEquals(SimulatorState.INITIALIZED, simulator.snapshot().simulatorState());
    }

    @Test
    void loadsAtUserStartAndDefensivelyAcceptsProgram() {
        simulator.initialize(128, 16);
        List<Instruction> input = new ArrayList<>(List.of(
                new MovInstruction(RegisterName.AX, 5), new LoadInstruction(RegisterName.AX)));
        simulator.loadProgram(input);
        input.clear();
        var snapshot = simulator.snapshot();
        assertEquals(SimulatorState.PROGRAM_LOADED, snapshot.simulatorState());
        assertEquals(new SimulatorSnapshot.ProcessSnapshot(1, "READY", 16, 2, 18, 16),
                snapshot.process().orElseThrow());
        assertEquals(List.of(new SimulatorSnapshot.ProgramEntry(16, "MOV AX, 5"),
                new SimulatorSnapshot.ProgramEntry(17, "LOAD AX")), snapshot.program());
        assertEquals(Optional.of("MOV AX, 5"), snapshot.memory().get(16).content());
        assertEquals(Optional.of("LOAD AX"), snapshot.memory().get(17).content());
        assertTrue(snapshot.memory().get(18).content().isEmpty());
        assertEquals(128, snapshot.memory().size());
    }

    @Test
    void rejectedLoadsPreserveInitializedSessionAndAllowRetry() {
        simulator.initialize(128, 127);
        var before = simulator.snapshot();
        assertThrows(NullPointerException.class, () -> simulator.loadProgram(null));
        assertEquals(before, simulator.snapshot());
        assertThrows(NullPointerException.class,
                () -> simulator.loadProgram(Collections.singletonList(null)));
        assertEquals(before, simulator.snapshot());
        assertThrows(ProgramLoadException.class, () -> simulator.loadProgram(List.of()));
        assertEquals(before, simulator.snapshot());
        assertThrows(ProgramLoadException.class, () -> simulator.loadProgram(List.of(
                new LoadInstruction(RegisterName.AX), new LoadInstruction(RegisterName.BX))));
        assertEquals(before, simulator.snapshot());
        simulator.loadProgram(List.of(new LoadInstruction(RegisterName.AX)));
        assertEquals(SimulatorState.PROGRAM_LOADED, simulator.snapshot().simulatorState());
    }

    @Test
    void startDoesNotExecuteAndEachStepUsesIrUntilImmediateFinish() {
        load(List.of(new MovInstruction(RegisterName.AX, 5), new LoadInstruction(RegisterName.AX)));
        var loaded = simulator.snapshot();
        simulator.start();
        var started = simulator.snapshot();
        assertEquals(SimulatorState.RUNNING, started.simulatorState());
        assertEquals(loaded.cpu(), started.cpu());
        assertEquals(loaded.process(), started.process());
        assertTrue(started.currentInstruction().isEmpty());

        simulator.step();
        var first = simulator.snapshot();
        assertEquals(SimulatorState.RUNNING, first.simulatorState());
        assertEquals(5, first.cpu().orElseThrow().ax());
        assertEquals(0, first.cpu().orElseThrow().accumulator());
        assertEquals(17, first.cpu().orElseThrow().programCounter());
        assertEquals(17, first.process().orElseThrow().savedProgramCounter());
        assertEquals("RUNNING", first.process().orElseThrow().processState());
        assertEquals(Optional.of("MOV AX, 5"), first.cpu().orElseThrow().instructionRegister());
        assertEquals(new SimulatorSnapshot.InstructionSnapshot(
                "MOV AX, 5", "MOV", "AX, 5", "00000000", Optional.of("00000101")),
                first.currentInstruction().orElseThrow());

        simulator.step();
        var last = simulator.snapshot();
        assertEquals(SimulatorState.FINISHED, last.simulatorState());
        assertEquals("TERMINATED", last.process().orElseThrow().processState());
        assertEquals(18, last.cpu().orElseThrow().programCounter());
        assertEquals(18, last.process().orElseThrow().savedProgramCounter());
        assertEquals(5, last.cpu().orElseThrow().accumulator());
        assertEquals(new SimulatorSnapshot.InstructionSnapshot(
                "LOAD AX", "LOAD", "AX", "00100000", Optional.empty()),
                last.currentInstruction().orElseThrow());
        assertThrows(IllegalStateException.class, simulator::step);
        assertEquals(last, simulator.snapshot());
    }

    @Test
    void formatsEveryInstructionConsistentlyAcrossViews() {
        load(List.of(new MovInstruction(RegisterName.AX, -5), new LoadInstruction(RegisterName.AX),
                new StoreInstruction(RegisterName.BX), new AddInstruction(RegisterName.CX),
                new SubInstruction(RegisterName.DX)));
        var expected = List.of("MOV AX, -5", "LOAD AX", "STORE BX", "ADD CX", "SUB DX");
        var words = List.of("00000000", "00100000", "01001000", "01110000", "10011000");
        simulator.start();
        for (int index = 0; index < expected.size(); index++) {
            simulator.step();
            var snapshot = simulator.snapshot();
            var instruction = snapshot.currentInstruction().orElseThrow();
            assertEquals(expected.get(index), instruction.semanticInstruction());
            assertEquals(Optional.of(expected.get(index)), snapshot.cpu().orElseThrow().instructionRegister());
            assertEquals(expected.get(index), snapshot.program().get(index).instruction());
            assertEquals(Optional.of(expected.get(index)), snapshot.memory().get(16 + index).content());
            assertEquals(words.get(index), instruction.word1());
            assertEquals(index == 0 ? Optional.of("10000101") : Optional.empty(), instruction.word2());
        }
    }

    @Test
    void pauseAndResumeOnlyChangeSimulatorState() {
        load(List.of(new MovInstruction(RegisterName.AX, 5), new LoadInstruction(RegisterName.AX)));
        simulator.start();
        simulator.step();
        var running = simulator.snapshot();
        simulator.pause();
        var paused = simulator.snapshot();
        assertEquals(SimulatorState.PAUSED, paused.simulatorState());
        assertEquals(running.process(), paused.process());
        assertEquals(running.cpu(), paused.cpu());
        assertThrows(IllegalStateException.class, simulator::step);
        assertEquals(paused, simulator.snapshot());
        simulator.resume();
        assertEquals(running, simulator.snapshot());
        simulator.step();
        assertEquals(SimulatorState.FINISHED, simulator.snapshot().simulatorState());
    }

    @Test
    void applicationGuardsRejectBeforeMutation() {
        var configuring = simulator.snapshot();
        assertThrows(IllegalStateException.class, () -> simulator.loadProgram(List.of()));
        assertThrows(IllegalStateException.class, simulator::step);
        assertThrows(IllegalStateException.class, simulator::start);
        assertEquals(configuring, simulator.snapshot());
        load(List.of(new LoadInstruction(RegisterName.AX)));
        var loaded = simulator.snapshot();
        assertThrows(IllegalStateException.class, () -> simulator.initialize(256, 32));
        assertThrows(IllegalStateException.class,
                () -> simulator.loadProgram(List.of(new LoadInstruction(RegisterName.BX))));
        assertThrows(IllegalStateException.class, simulator::step);
        assertEquals(loaded, simulator.snapshot());
    }

    @Test
    void executionFailureRetainsInspectableSessionAndResetRecovers() {
        load(List.of(new MovInstruction(RegisterName.AX, 127), new LoadInstruction(RegisterName.AX),
                new AddInstruction(RegisterName.AX)));
        simulator.start();
        simulator.step();
        simulator.step();
        var before = simulator.snapshot();
        assertThrows(ExecutionEngineException.class, simulator::step);
        var failed = simulator.snapshot();
        assertEquals(SimulatorState.ERROR, failed.simulatorState());
        assertEquals(before.memory(), failed.memory());
        assertEquals(before.program(), failed.program());
        assertEquals(before.process(), failed.process());
        assertEquals(127, failed.cpu().orElseThrow().accumulator());
        assertEquals(18, failed.cpu().orElseThrow().programCounter());
        assertEquals("ADD AX", failed.currentInstruction().orElseThrow().semanticInstruction());
        assertThrows(IllegalStateException.class, simulator::step);
        assertEquals(failed, simulator.snapshot());
        simulator.reset();
        assertEmptySession(simulator.snapshot());
        simulator.initialize(128, 16);
        assertTrue(simulator.snapshot().memory().stream().allMatch(row -> row.content().isEmpty()));
    }

    @Test
    void resetDiscardsSuccessfulSessionAndHistoricalSnapshotsStayImmutable() {
        load(List.of(new MovInstruction(RegisterName.AX, 5)));
        var historical = simulator.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> historical.program().clear());
        assertThrows(UnsupportedOperationException.class, () -> historical.memory().clear());
        simulator.start();
        simulator.step();
        simulator.reset();
        assertEmptySession(simulator.snapshot());
        assertEquals(SimulatorState.PROGRAM_LOADED, historical.simulatorState());
        assertEquals(0, historical.cpu().orElseThrow().ax());
        assertEquals("READY", historical.process().orElseThrow().processState());
        assertEquals(Optional.of("MOV AX, 5"), historical.memory().get(16).content());
        simulator.reset();
        assertEmptySession(simulator.snapshot());
    }

    @Test
    void snapshotCopiesInputListsAndRejectsNullOptionals() {
        var programs = new ArrayList<>(List.of(new SimulatorSnapshot.ProgramEntry(16, "LOAD AX")));
        var memory = new ArrayList<>(List.of(new SimulatorSnapshot.MemoryEntry(16, "USER", Optional.empty())));
        var snapshot = new SimulatorSnapshot(SimulatorState.CONFIGURING, Optional.empty(),
                Optional.empty(), Optional.empty(), programs, memory);
        programs.clear();
        memory.clear();
        assertEquals(1, snapshot.program().size());
        assertEquals(1, snapshot.memory().size());
        assertThrows(NullPointerException.class, () -> new SimulatorSnapshot(
                SimulatorState.CONFIGURING, null, Optional.empty(), Optional.empty(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new SimulatorSnapshot(
                SimulatorState.CONFIGURING, Optional.empty(), null, Optional.empty(), List.of(), List.of()));
        assertThrows(NullPointerException.class, () -> new SimulatorSnapshot(
                SimulatorState.CONFIGURING, Optional.empty(), Optional.empty(), null, List.of(), List.of()));
        assertThrows(NullPointerException.class,
                () -> new SimulatorSnapshot.CpuSnapshot(0, 0, 0, 0, 0, 0, null));
        assertThrows(NullPointerException.class,
                () -> new SimulatorSnapshot.InstructionSnapshot("LOAD AX", "LOAD", "AX", "00100000", null));
        assertThrows(NullPointerException.class,
                () -> new SimulatorSnapshot.MemoryEntry(0, "KERNEL", null));
    }

    private void load(List<Instruction> instructions) {
        simulator.initialize(128, 16);
        simulator.loadProgram(instructions);
    }

    private void assertEmptySession(SimulatorSnapshot snapshot) {
        assertEquals(SimulatorState.CONFIGURING, snapshot.simulatorState());
        assertTrue(snapshot.cpu().isEmpty());
        assertTrue(snapshot.currentInstruction().isEmpty());
        assertTrue(snapshot.process().isEmpty());
        assertTrue(snapshot.program().isEmpty());
        assertTrue(snapshot.memory().isEmpty());
    }
}
