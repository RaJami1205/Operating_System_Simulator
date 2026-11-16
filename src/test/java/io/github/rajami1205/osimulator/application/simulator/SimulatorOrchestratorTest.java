package io.github.rajami1205.osimulator.application.simulator;

import static org.junit.jupiter.api.Assertions.*;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.process.AdmissionResult;
import io.github.rajami1205.osimulator.model.job.JobState;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.instruction.AddInstruction;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.instruction.LoadInstruction;
import io.github.rajami1205.osimulator.model.instruction.MovInstruction;
import io.github.rajami1205.osimulator.model.instruction.StoreInstruction;
import io.github.rajami1205.osimulator.model.instruction.SubInstruction;
import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryConfigurationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;

class SimulatorOrchestratorTest {
    private final SimulatorOrchestrator simulator = new SimulatorOrchestrator(
            new ProgramLoader(), new ExecutionEngine());

    @Test
    void requiresAllDependencies() {
        assertThrows(NullPointerException.class, () -> new SimulatorOrchestrator(
                null, new ExecutionEngine()));
        assertThrows(NullPointerException.class, () -> new SimulatorOrchestrator(
                new ProgramLoader(), null));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3, 4, 5})
    void initializesConfiguredTopologyAndResetAllowsSingleCpu(int cpus) {
        var configuration = new SimulatorConfiguration(new MemoryConfiguration(1024, 700), 512, 64, cpus);
        simulator.initialize(configuration);
        assertEquals(cpus, simulator.snapshot().cpus().size());
        assertEquals(configuration, simulator.configuration().orElseThrow());
        assertEquals(0, simulator.snapshot().simulationTimeSeconds().orElseThrow());
        assertTrue(simulator.snapshot().cpus().stream().allMatch(cpu -> cpu.ownerPid().isEmpty() && cpu.cpuTicks() == 0));
        simulator.reset();
        assertTrue(simulator.snapshot().cpus().isEmpty());
        simulator.initialize(SimulatorConfiguration.defaults());
        assertEquals(SimulatorState.INITIALIZED, simulator.snapshot().simulatorState());
        assertEquals(1, simulator.configuration().orElseThrow().cpuCount());
    }

    @Test
    void startsWithoutSessionAndInitializesRealCpuAndMemory() {
        assertEmptySession(simulator.snapshot());
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 512, 64));
        var snapshot = simulator.snapshot();
        assertEquals(SimulatorState.INITIALIZED, snapshot.simulatorState());
        assertEquals(new SimulatorSnapshot.CpuSnapshot(0, 0, 0, 0, 0, 0, Optional.empty()),
                snapshot.cpu().orElseThrow());
        assertTrue(snapshot.process().isEmpty());
        assertTrue(snapshot.currentInstruction().isEmpty());
        assertTrue(snapshot.program().isEmpty());
        assertEquals(256, snapshot.memory().size());
        for (int address = 0; address < 256; address++) {
            var row = snapshot.memory().get(address);
            assertEquals(address, row.address());
            assertEquals(address < 140 ? "KERNEL" : "USER", row.region());
            assertTrue(row.content().isEmpty());
        }
    }

    @Test
    void invalidConfigurationIsRecoverable() {
        var before = simulator.snapshot();
        assertThrows(NullPointerException.class, () -> simulator.initialize(null));
        assertTrue(simulator.configuration().isEmpty());
        assertEquals(before, simulator.snapshot());
        assertThrows(InvalidMemoryConfigurationException.class, () -> simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(127, 32), 512, 64)));
        assertEquals(before, simulator.snapshot());
        assertThrows(InvalidMemoryConfigurationException.class, () -> simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(128, 0), 512, 64)));
        assertEquals(before, simulator.snapshot());
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 512, 64));
        assertEquals(SimulatorState.INITIALIZED, simulator.snapshot().simulatorState());
    }

    @Test
    void loadsAtUserStartAndDefensivelyAcceptsProgram() {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 512, 64));
        List<Instruction> input = new ArrayList<>(List.of(
                new MovInstruction(RegisterName.AX, 5), new LoadInstruction(RegisterName.AX)));
        simulator.loadProgram(input);
        input.clear();
        var snapshot = simulator.snapshot();
        assertEquals(SimulatorState.PROGRAM_LOADED, snapshot.simulatorState());
        assertTrue(snapshot.process().isEmpty());
        assertTrue(snapshot.program().isEmpty());
        assertTrue(snapshot.memory().stream().allMatch(row -> row.content().isEmpty()));
        simulator.start();
        assertTrue(simulator.snapshot().process().isEmpty());
        simulator.step();
        snapshot = simulator.snapshot();
        assertEquals(new SimulatorSnapshot.ProcessSnapshot(1, "RUNNING", 140, 2, 142, 4097),
                snapshot.process().orElseThrow());
        assertEquals(List.of(new SimulatorSnapshot.ProgramEntry(140, "MOV AX, 5"),
                new SimulatorSnapshot.ProgramEntry(141, "LOAD AX")), snapshot.program());
        assertEquals(Optional.of("MOV AX, 5"), snapshot.memory().get(140).content());
        assertEquals(Optional.of("LOAD AX"), snapshot.memory().get(141).content());
        assertTrue(snapshot.memory().get(142).content().isEmpty());
        assertEquals(256, snapshot.memory().size());
    }

    @Test
    void rejectedLoadsPreserveInitializedSessionAndAllowRetry() {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 255), 512, 64));
        var before = simulator.snapshot();
        assertThrows(NullPointerException.class, () -> simulator.loadProgram(null));
        assertEquals(before, simulator.snapshot());
        assertThrows(NullPointerException.class,
                () -> simulator.loadProgram(Collections.singletonList(null)));
        assertEquals(before, simulator.snapshot());
        assertThrows(IllegalArgumentException.class, () -> simulator.loadProgram(List.of()));
        assertEquals(before, simulator.snapshot());
        simulator.loadProgram(List.of(new LoadInstruction(RegisterName.AX), new LoadInstruction(RegisterName.BX)));
        simulator.start();
        assertEquals(new RuntimeStepResult.Idle(RuntimeStatus.WAITING_FOR_CAPACITY), simulator.step());
        assertEquals(before.cpu(), simulator.snapshot().cpu());
        assertEquals(JobState.PENDING, simulator.jobs().getFirst().state());
    }

    @Test
    void startDoesNotExecuteAndCompletedInstructionsPreserveIrUntilFinish() {
        load(List.of(new MovInstruction(RegisterName.AX, 5), new LoadInstruction(RegisterName.AX)));
        var loaded = simulator.snapshot();
        simulator.start();
        var started = simulator.snapshot();
        assertEquals(SimulatorState.RUNNING, started.simulatorState());
        assertEquals(loaded.cpu(), started.cpu());
        assertEquals(loaded.process(), started.process());
        assertTrue(started.currentInstruction().isEmpty());

        completeInstruction();
        var first = simulator.snapshot();
        assertEquals(SimulatorState.RUNNING, first.simulatorState());
        assertEquals(5, first.cpu().orElseThrow().ax());
        assertEquals(0, first.cpu().orElseThrow().accumulator());
        assertEquals(4097, first.cpu().orElseThrow().programCounter());
        assertEquals(4097, first.process().orElseThrow().savedProgramCounter());
        assertEquals("RUNNING", first.process().orElseThrow().processState());
        assertEquals(Optional.of("MOV AX, 5"), first.cpu().orElseThrow().instructionRegister());
        assertEquals(new SimulatorSnapshot.InstructionSnapshot(
                "MOV AX, 5", "MOV", "AX, 5"),
                first.currentInstruction().orElseThrow());

        completeInstruction();
        var last = simulator.snapshot();
        assertEquals(SimulatorState.FINISHED, last.simulatorState());
        assertTrue(last.process().isEmpty());
        assertTrue(last.program().isEmpty());
        assertEquals(4098, last.cpu().orElseThrow().programCounter());
        assertEquals(4098, simulator.completedProcesses().getFirst().finalContext().programCounter());
        assertEquals(5, last.cpu().orElseThrow().accumulator());
        assertTrue(last.currentInstruction().isEmpty());
        assertEquals(Optional.of("LOAD AX"), last.cpu().orElseThrow().instructionRegister());
        assertThrows(IllegalStateException.class, simulator::step);
        assertEquals(last, simulator.snapshot());
    }

    @Test
    void formatsEveryInstructionConsistentlyAcrossViews() {
        load(List.of(new MovInstruction(RegisterName.AX, -5), new LoadInstruction(RegisterName.AX),
                new StoreInstruction(RegisterName.BX), new AddInstruction(RegisterName.CX),
                new SubInstruction(RegisterName.DX), new MovInstruction(RegisterName.AX, 0)));
        var expected = List.of("MOV AX, -5", "LOAD AX", "STORE BX", "ADD CX", "SUB DX");
        var opcodes = List.of("MOV", "LOAD", "STORE", "ADD", "SUB");
        var operands = List.of("AX, -5", "AX", "BX", "CX", "DX");
        simulator.start();
        for (int index = 0; index < expected.size(); index++) {
            completeInstruction();
            var snapshot = simulator.snapshot();
            var instruction = snapshot.currentInstruction().orElseThrow();
            assertEquals(expected.get(index), instruction.semanticInstruction());
            assertEquals(Optional.of(expected.get(index)), snapshot.cpu().orElseThrow().instructionRegister());
            assertEquals(expected.get(index), snapshot.program().get(index).instruction());
            assertEquals(Optional.of(expected.get(index)), snapshot.memory().get(140 + index).content());
            assertEquals(opcodes.get(index), instruction.opcode());
            assertEquals(operands.get(index), instruction.operand());
        }
    }

    @Test
    void pauseAndResumeOnlyChangeSimulatorState() {
        load(List.of(new MovInstruction(RegisterName.AX, 5), new LoadInstruction(RegisterName.AX)));
        simulator.start();
        completeInstruction();
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
        completeInstruction();
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
        assertThrows(IllegalStateException.class, () -> simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 512, 64)));
        assertThrows(IllegalStateException.class, simulator::step);
        assertEquals(loaded, simulator.snapshot());
        simulator.start();
        var started = simulator.snapshot();
        assertThrows(IllegalStateException.class,
                () -> simulator.loadProgram(List.of(new LoadInstruction(RegisterName.BX))));
        assertEquals(started, simulator.snapshot());
    }

    @Test
    void executionFailureRetainsInspectableSessionAndResetRecovers() {
        load(List.of(new MovInstruction(RegisterName.AX, 32767), new LoadInstruction(RegisterName.AX),
                new AddInstruction(RegisterName.AX)));
        simulator.start();
        completeInstruction();
        completeInstruction();
        var before = simulator.snapshot();
        assertThrows(ExecutionEngineException.class, simulator::step);
        var failed = simulator.snapshot();
        assertEquals(SimulatorState.ERROR, failed.simulatorState());
        assertEquals(before.memory(), failed.memory());
        assertEquals(before.program(), failed.program());
        assertEquals(before.process(), failed.process());
        assertEquals(32767, failed.cpu().orElseThrow().accumulator());
        assertEquals(4098, failed.cpu().orElseThrow().programCounter());
        assertEquals("ADD AX", failed.currentInstruction().orElseThrow().semanticInstruction());
        assertThrows(IllegalStateException.class, simulator::step);
        assertEquals(failed, simulator.snapshot());
        simulator.reset();
        assertEmptySession(simulator.snapshot());
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 512, 64));
        assertTrue(simulator.snapshot().memory().stream().allMatch(row -> row.content().isEmpty()));
    }

    @Test
    void resetDiscardsSuccessfulSessionAndHistoricalSnapshotsStayImmutable() {
        load(List.of(new MovInstruction(RegisterName.AX, 5), new LoadInstruction(RegisterName.AX)));
        simulator.start();
        completeInstruction();
        var historical = simulator.snapshot();
        assertThrows(UnsupportedOperationException.class, () -> historical.program().clear());
        assertThrows(UnsupportedOperationException.class, () -> historical.memory().clear());
        completeInstruction();
        simulator.reset();
        assertEmptySession(simulator.snapshot());
        assertEquals(SimulatorState.RUNNING, historical.simulatorState());
        assertEquals(5, historical.cpu().orElseThrow().ax());
        assertEquals("RUNNING", historical.process().orElseThrow().processState());
        assertEquals(Optional.of("MOV AX, 5"), historical.memory().get(140).content());
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
                () -> new SimulatorSnapshot.InstructionSnapshot(null, "LOAD", "AX"));
        assertThrows(NullPointerException.class,
                () -> new SimulatorSnapshot.InstructionSnapshot("LOAD AX", null, "AX"));
        assertThrows(NullPointerException.class,
                () -> new SimulatorSnapshot.InstructionSnapshot("LOAD AX", "LOAD", null));
        assertThrows(NullPointerException.class,
                () -> new SimulatorSnapshot.MemoryEntry(0, "KERNEL", null));
    }

    @Test
    void retainsConfigurationUntilResetAndAcceptsNewSession() {
        var first = SimulatorConfiguration.defaults();
        simulator.initialize(first);
        assertEquals(first, simulator.configuration().orElseThrow());
        var second = new SimulatorConfiguration(new MemoryConfiguration(512, 140), 1024, 128);
        var before = simulator.snapshot();
        assertThrows(IllegalStateException.class, () -> simulator.initialize(second));
        assertEquals(first, simulator.configuration().orElseThrow());
        assertEquals(before, simulator.snapshot());
        simulator.reset();
        assertEmptySession(simulator.snapshot());
        simulator.initialize(second);
        assertEquals(second, simulator.configuration().orElseThrow());
        assertEquals(512, simulator.snapshot().memory().size());
        assertEquals("KERNEL", simulator.snapshot().memory().get(139).region());
        assertEquals("USER", simulator.snapshot().memory().get(140).region());
    }

    @Test
    void formatsKernelPcbAsImmutableDescriptiveText() {
        var pcb = new io.github.rajami1205.osimulator.model.process.ProcessControlBlock(7, 32, 1);
        var text = simulator.contentText(io.github.rajami1205.osimulator.model.memory.PcbMemoryImage.capture(pcb).cells().getFirst());
        assertEquals(Optional.of("PCB 7 · PID: 7"), text);
        pcb.changeState(io.github.rajami1205.osimulator.model.process.ProcessState.TERMINATED);
        assertEquals(Optional.of("PCB 7 · PID: 7"), text);
    }

    @Test
    void createsAndDiscardsSecondaryStorageIncludingCompatibilityProgram() throws Exception {
        // Inspect ownership without exposing mutable session state to Presentation.
        var field = SimulatorOrchestrator.class.getDeclaredField("secondaryStorage");
        field.setAccessible(true);
        assertNull(field.get(simulator));
        simulator.initialize(SimulatorConfiguration.defaults());
        var first = assertInstanceOf(
                io.github.rajami1205.osimulator.model.storage.SecondaryStorage.class, field.get(simulator));
        assertEquals(512, first.size());
        assertEquals(64, first.virtualMemoryPositions());
        assertEquals(448, first.swapStart());
        simulator.loadProgram(List.of(new LoadInstruction(RegisterName.AX)));
        assertEquals(1, first.entries().size());
        assertThrows(IllegalStateException.class, () -> simulator.initialize(SimulatorConfiguration.defaults()));
        assertSame(first, field.get(simulator));
        simulator.reset();
        assertNull(field.get(simulator));
        simulator.reset();
        assertNull(field.get(simulator));
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 128, 127));
        var second = assertInstanceOf(
                io.github.rajami1205.osimulator.model.storage.SecondaryStorage.class, field.get(simulator));
        assertNotSame(first, second);
        assertEquals(128, second.size());
        assertEquals(127, second.virtualMemoryPositions());
        assertEquals(second.dataStart(), second.dataEndExclusive());
        assertEquals(SimulatorState.INITIALIZED, simulator.snapshot().simulatorState());
    }

    @Test
    void submissionsPrepareWorkloadWithoutChangingCpuAndResetRestartsIdentity() {
        var image = new ProgramImage("p", List.of(new LoadInstruction(RegisterName.AX)));
        assertTrue(simulator.jobs().isEmpty());
        assertThrows(IllegalStateException.class, () -> simulator.submitProgram(image));
        simulator.initialize(SimulatorConfiguration.defaults());
        var before = simulator.snapshot();
        assertEquals(1, simulator.submitProgram(image).jobId());
        assertEquals(2, simulator.submitProgram(new ProgramImage("q", image.instructions())).jobId());
        assertEquals(before.cpu(), simulator.snapshot().cpu());
        assertEquals(before.memory(), simulator.snapshot().memory());
        assertEquals(SimulatorState.PROGRAM_LOADED, simulator.snapshot().simulatorState());
        var prepared = simulator.snapshot();
        var history = simulator.jobs();
        assertThrows(UnsupportedOperationException.class, history::clear);
        assertThrows(io.github.rajami1205.osimulator.model.storage.exception.StorageException.class,
                () -> simulator.submitProgram(image));
        assertEquals(prepared, simulator.snapshot());
        simulator.loadProgram(image.instructions());
        assertEquals(3, simulator.jobs().size());
        simulator.start();
        assertThrows(IllegalStateException.class, () -> simulator.submitProgram(image));
        simulator.pause();
        assertThrows(IllegalStateException.class, () -> simulator.submitProgram(image));
        simulator.resume();
        for (int i = 0; i < 3; i++) simulator.step();
        assertEquals(SimulatorState.FINISHED, simulator.snapshot().simulatorState());
        assertThrows(IllegalStateException.class, () -> simulator.submitProgram(image));
        assertTrue(history.stream().allMatch(job -> job.state() == JobState.PENDING));
        simulator.reset();
        assertTrue(simulator.jobs().isEmpty());
        assertEquals(2, history.size());
        simulator.initialize(SimulatorConfiguration.defaults());
        assertEquals(1, simulator.submitProgram(image).jobId());
    }

    @Test
    void admissionPreservesIdleCpuAndUsesSameWorkloadAsCompatibilityLoading() {
        assertThrows(IllegalStateException.class, simulator::attemptNextAdmission);
        simulator.initialize(SimulatorConfiguration.defaults());
        assertTrue(simulator.attemptNextAdmission().isEmpty());
        var before = simulator.snapshot();
        var program = new ProgramImage("resident", List.of(new LoadInstruction(RegisterName.AX)));
        simulator.submitProgram(program);
        var history = simulator.jobs();
        assertEquals(Optional.of(new AdmissionResult.Admitted(1)), simulator.attemptNextAdmission());
        var after = simulator.snapshot();
        assertEquals(SimulatorState.PROGRAM_LOADED, after.simulatorState());
        assertEquals(before.cpu(), after.cpu());
        assertEquals(before.currentInstruction(), after.currentInstruction());
        assertTrue(after.process().isEmpty());
        assertTrue(after.program().isEmpty());
        assertEquals(Optional.of("PCB 1 · PID: 1"), after.memory().getFirst().content());
        assertEquals(Optional.of("LOAD AX"), after.memory().get(140).content());
        assertEquals(JobState.ADMITTED, simulator.jobs().getFirst().state());
        assertEquals(JobState.PENDING, history.getFirst().state());
        assertThrows(IllegalStateException.class, simulator::step);
        simulator.loadProgram(program.instructions());
        assertEquals(Optional.of(new AdmissionResult.Admitted(2)), simulator.attemptNextAdmission());
        simulator.start();
        for (int i = 0; i < 2; i++) simulator.step();
        assertEquals(SimulatorState.FINISHED, simulator.snapshot().simulatorState());
        simulator.reset();
        assertEmptySession(simulator.snapshot());
        assertTrue(simulator.jobs().isEmpty());
        assertThrows(IllegalStateException.class, simulator::attemptNextAdmission);
        simulator.initialize(SimulatorConfiguration.defaults());
        simulator.loadProgram(program.instructions());
        assertEquals(Optional.of(new AdmissionResult.Admitted(1)), simulator.attemptNextAdmission());
        simulator.start();
        completeInstruction();
        assertEquals(SimulatorState.FINISHED, simulator.snapshot().simulatorState());
    }

    @Test
    void waitingAdmissionDoesNotBypassHeadThroughCompatibilityLoad() {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 512, 64));
        simulator.submitProgram(new ProgramImage("tooLarge", Collections.nCopies(117, new LoadInstruction(RegisterName.AX))));
        var before = simulator.snapshot();
        assertEquals(Optional.of(new AdmissionResult.Waiting(AdmissionResult.Reason.INSUFFICIENT_USER_MEMORY)),
                simulator.attemptNextAdmission());
        assertEquals(before, simulator.snapshot());
        assertEquals(JobState.PENDING, simulator.jobs().getFirst().state());
        simulator.loadProgram(List.of(new LoadInstruction(RegisterName.BX)));
        simulator.start();
        assertEquals(new RuntimeStepResult.Idle(RuntimeStatus.WAITING_FOR_CAPACITY), simulator.step());
        assertTrue(simulator.jobs().stream().allMatch(job -> job.state() == JobState.PENDING));
    }

    @Test
    void schedulingSelectsWithoutDispatchAndResetDiscardsCandidates() {
        assertThrows(IllegalStateException.class, simulator::selectNextReadyProcess);
        simulator.initialize(SimulatorConfiguration.defaults());
        assertTrue(simulator.selectNextReadyProcess().isEmpty());
        var image = new ProgramImage("first", List.of(new LoadInstruction(RegisterName.AX)));
        simulator.submitProgram(image);
        simulator.submitProgram(new ProgramImage("second", image.instructions()));
        simulator.attemptNextAdmission();
        simulator.attemptNextAdmission();
        var before = simulator.snapshot();
        var jobsBefore = simulator.jobs();
        assertEquals(Optional.of(1), simulator.selectNextReadyProcess());
        assertEquals(Optional.of(1), simulator.selectNextReadyProcess());
        assertEquals(before, simulator.snapshot());
        assertEquals(jobsBefore, simulator.jobs());
        assertEquals(SimulatorState.PROGRAM_LOADED, simulator.snapshot().simulatorState());
        assertTrue(simulator.snapshot().process().isEmpty());
        simulator.reset();
        assertThrows(IllegalStateException.class, simulator::selectNextReadyProcess);
        simulator.initialize(SimulatorConfiguration.defaults());
        assertTrue(simulator.selectNextReadyProcess().isEmpty());
        simulator.submitProgram(image);
        simulator.attemptNextAdmission();
        assertEquals(Optional.of(1), simulator.selectNextReadyProcess());
        simulator.reset();
        simulator.initialize(SimulatorConfiguration.defaults());
        simulator.loadProgram(image.instructions());
        assertTrue(simulator.selectNextReadyProcess().isEmpty());
        simulator.start();
        assertThrows(IllegalStateException.class, simulator::selectNextReadyProcess);
        completeInstruction();
        assertEquals(SimulatorState.FINISHED, simulator.snapshot().simulatorState());
    }

    @Test
    void pauseResumePreservesCompletedInstructionsAndResetStartsFresh() {
        load(List.of(new MovInstruction(RegisterName.AX, 5), new AddInstruction(RegisterName.AX),
                new io.github.rajami1205.osimulator.model.instruction.IncInstruction()));
        simulator.start();
        simulator.step();
        simulator.step();
        var completed = simulator.snapshot();
        assertEquals(4098, completed.cpu().orElseThrow().programCounter());
        assertEquals(5, completed.cpu().orElseThrow().accumulator());
        assertEquals("ADD AX", completed.currentInstruction().orElseThrow().semanticInstruction());
        assertEquals(2, completed.cpuTicks().orElseThrow());
        simulator.pause();
        assertThrows(IllegalStateException.class, simulator::step);
        simulator.resume();
        assertEquals(completed, simulator.snapshot());
        simulator.step();
        assertEquals(SimulatorState.FINISHED, simulator.snapshot().simulatorState());
        assertEquals(6, simulator.snapshot().cpu().orElseThrow().accumulator());
        assertEquals(3, simulator.snapshot().cpuTicks().orElseThrow());
        simulator.reset();
        load(List.of(new AddInstruction(RegisterName.AX)));
        simulator.start();
        simulator.step();
        assertEquals(SimulatorState.FINISHED, simulator.snapshot().simulatorState());
        assertEquals(1, simulator.snapshot().cpuTicks().orElseThrow());
        assertEquals(1, simulator.snapshot().simulationTimeSeconds().orElseThrow());
    }

    @Test
    void formatsAndExecutesTransferArithmeticFormsWithoutTrailingSpaces() {
        var expected = List.of("MOV AX, 5", "MOV BX, AX", "INC", "INC AX", "DEC", "DEC BX", "SWAP AX, BX");
        var parser = new io.github.rajami1205.osimulator.infrastructure.asm.AsmParser();
        load(parser.parse(expected));
        simulator.start();
        for (int index = 0; index < expected.size(); index++) {
            simulator.step();
            var snapshot = simulator.snapshot();
            if (snapshot.ownerPid().isPresent()) assertEquals(expected.get(index), snapshot.currentInstruction().orElseThrow().semanticInstruction());
            else assertTrue(snapshot.currentInstruction().isEmpty());
            assertEquals(Optional.of(expected.get(index)), snapshot.cpu().orElseThrow().instructionRegister());
            if (index < expected.size() - 1) {
                assertEquals(expected.get(index), snapshot.program().get(index).instruction());
                assertEquals(Optional.of(expected.get(index)), snapshot.memory().get(140 + index).content());
            } else {
                assertTrue(snapshot.program().isEmpty());
                assertTrue(snapshot.memory().stream().allMatch(row -> row.content().isEmpty()));
            }
            assertEquals(4096 + index + 1, snapshot.cpu().orElseThrow().programCounter());
            if (index == 2 || index == 4) assertEquals("", snapshot.currentInstruction().orElseThrow().operand());
            assertEquals(index == expected.size() - 1 ? SimulatorState.FINISHED : SimulatorState.RUNNING, snapshot.simulatorState());
        }
        assertEquals(4, simulator.snapshot().cpu().orElseThrow().ax());
        assertEquals(6, simulator.snapshot().cpu().orElseThrow().bx());
    }

    @Test
    void formatsControlFlowAndStackAcrossProgramMemoryAndCurrentInstruction() {
        var parser = new io.github.rajami1205.osimulator.infrastructure.asm.AsmParser();
        var expected = List.of("CMP AX, BX", "JMP +2", "JMP -2", "JMP 0", "JE +1", "JNE -1",
                "PARAM 1, 2, 3", "PUSH AX", "POP BX");
        for (String text : expected) {
            simulator.reset();
            load(parser.parse(List.of(text)));
            simulator.start();
            assertEquals(Optional.of(text), simulator.snapshot().memory().get(140).content());
            if (List.of("POP BX", "JMP +2", "JMP -2", "JNE -1").contains(text)) assertThrows(ExecutionEngineException.class, simulator::step);
            else simulator.step();
            if (simulator.snapshot().ownerPid().isPresent()) assertEquals(text, simulator.snapshot().currentInstruction().orElseThrow().semanticInstruction());
            else assertTrue(simulator.snapshot().currentInstruction().isEmpty());
            assertEquals(Optional.of(text), simulator.snapshot().cpu().orElseThrow().instructionRegister());
        }
    }

    private void completeInstruction() {
        simulator.step();
    }

    private void load(List<Instruction> instructions) {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 512, 64));
        simulator.loadProgram(instructions);
    }

    private void assertEmptySession(SimulatorSnapshot snapshot) {
        assertTrue(simulator.configuration().isEmpty());
        assertEquals(SimulatorState.CONFIGURING, snapshot.simulatorState());
        assertTrue(snapshot.cpu().isEmpty());
        assertTrue(snapshot.currentInstruction().isEmpty());
        assertTrue(snapshot.process().isEmpty());
        assertTrue(snapshot.program().isEmpty());
        assertTrue(snapshot.memory().isEmpty());
    }
}
