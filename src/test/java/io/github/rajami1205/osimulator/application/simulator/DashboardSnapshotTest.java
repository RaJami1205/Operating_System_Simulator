package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.scheduling.ReadyQueue;
import io.github.rajami1205.osimulator.model.storage.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DashboardSnapshotTest {
    private final SimulatorOrchestrator simulator = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine());
    private void initialize() { simulator.initialize(SimulatorConfiguration.defaults()); }
    private void submit(String name, String... lines) { simulator.submitProgram(new ProgramImage(name, new AsmParser().parse(List.of(lines)))); }
    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target));
    }
    @Test void lifecycleOwnerAndCompleteCpuRemainDistinct() {
        var configuring = simulator.snapshot();
        assertTrue(configuring.cpu().isEmpty()); assertTrue(configuring.configuration().isEmpty());
        assertTrue(configuring.runtimeStatus().isEmpty());
        initialize(); var initialized = simulator.snapshot();
        assertEquals(Optional.of(SimulatorConfiguration.defaults()), initialized.configuration());
        assertTrue(initialized.runtimeStatus().isEmpty()); assertEquals("0", initialized.cpu().orElseThrow().alText());
        submit("first.asm", "MOV DX, \"file.txt\"", "MOV AL, \"hello\"", "MOV AH, 64", "CMP AX, BX", "INC");
        assertTrue(simulator.snapshot().runtimeStatus().isEmpty());
        simulator.start(); assertEquals(Optional.of(RuntimeStatus.RUNNABLE), simulator.snapshot().runtimeStatus());
        assertTrue(simulator.snapshot().ownerPid().isEmpty());
        simulator.step(); simulator.step(); simulator.step(); simulator.step(); simulator.step();
        var active = simulator.snapshot(); var cpu = active.cpu().orElseThrow();
        assertEquals(Optional.of(1), active.ownerPid()); assertEquals("file.txt", cpu.dxText()); assertEquals("hello", cpu.alText());
        assertEquals(64, cpu.ah()); assertEquals(0, cpu.ax()); assertEquals(0, cpu.bx()); assertEquals(0, cpu.cx());
        assertEquals(0, cpu.accumulator()); assertTrue(cpu.flags().equal()); assertFalse(cpu.flags().overflow());
        assertEquals("CMP AX, BX", active.currentInstruction().orElseThrow().semanticInstruction());
        assertEquals(5, active.program().size());
        simulator.pause(); assertEquals(Optional.of(RuntimeStatus.RUNNABLE), simulator.snapshot().runtimeStatus()); simulator.resume();
        simulator.step(); var finished = simulator.snapshot();
        assertEquals(Optional.of(RuntimeStatus.FINISHED), finished.runtimeStatus()); assertTrue(finished.ownerPid().isEmpty());
        assertTrue(finished.currentInstruction().isEmpty()); assertTrue(finished.process().isEmpty()); assertTrue(finished.program().isEmpty());
        assertEquals(Optional.of("INC"), finished.cpu().orElseThrow().instructionRegister());
        assertEquals(List.of(1), finished.completedProcesses().stream().map(SimulatorSnapshot.CompletedProcess::processId).toList());
        assertEquals("hello", finished.completedProcesses().getFirst().finalContext().alText());
    }
    @Test void processDetailsAreCopiesAndSuspendedBaseIsAbsent() throws Exception {
        initialize(); submit("one", "INC", "INC"); submit("two", "INC");
        simulator.attemptNextAdmission(); simulator.attemptNextAdmission();
        var pcb = field(simulator, "processTable", ProcessTable.class).find(1).orElseThrow();
        pcb.stack().pushAll(List.of(8, 3)); pcb.openFiles().open("z"); pcb.openFiles().open("a"); pcb.setPriority(9);
        var context = new CpuContext<Instruction>(0, Optional.of(new IncInstruction()), 1, 2, 3, 4,
                new TextRegisterValue("path"), 5, new TextRegisterValue("text"), new ConditionFlags(true, true));
        pcb.replaceCpuContext(context);
        var old = simulator.snapshot(); var row = old.processes().getFirst();
        assertEquals(List.of("one", "two"), old.jobs().stream().map(j -> j.programName()).toList());
        assertEquals(List.of(1, 2), old.processes().stream().map(SimulatorSnapshot.ProcessDetails::processId).toList());
        assertEquals(List.of(1, 2), old.readyQueue()); assertEquals(Optional.of(32), row.base());
        assertEquals(List.of("a", "z"), row.openFiles()); assertEquals(List.of(8, 3), row.stack());
        assertEquals(9, row.priority()); assertEquals(0, row.kernelAddress()); assertEquals(Optional.of(1), row.nextPcbAddress());
        assertEquals("path", row.savedContext().dxText()); assertEquals("text", row.savedContext().alText());
        pcb.stack().pop(); pcb.openFiles().close("a"); pcb.setPriority(0); pcb.replaceCpuContext(CpuContext.initial());
        assertEquals(List.of(8, 3), row.stack()); assertEquals(List.of("a", "z"), row.openFiles()); assertEquals(9, row.priority());
        assertThrows(UnsupportedOperationException.class, () -> row.stack().add(1));
        assertThrows(UnsupportedOperationException.class, () -> row.openFiles().clear());
        simulator.swapOut(1); var suspended = simulator.snapshot().processes().getFirst();
        assertEquals(SimulatorSnapshot.Residency.SUSPENDED, suspended.residency()); assertTrue(suspended.base().isEmpty());
        assertEquals(2, suspended.limit()); assertEquals(Optional.of(32), row.base());
        assertEquals(List.of(2), simulator.snapshot().readyQueue());
    }
    @Test void fifoScreenAndResetAreImmutableObservations() {
        initialize(); submit("one", "INT 09H", "INT 10H"); submit("two", "INT 09H", "INC"); simulator.start();
        for (int i = 0; i < 4; i++) simulator.step();
        var waiting = simulator.snapshot();
        assertEquals(List.of(new PendingKeyboardRequest(1, 0), new PendingKeyboardRequest(2, 0)), waiting.pendingKeyboardRequests());
        assertEquals(Optional.of(RuntimeStatus.WAITING_FOR_INPUT), waiting.runtimeStatus());
        simulator.swapOut(1); simulator.swapOut(2); simulator.submitKeyboardInput(7); simulator.submitKeyboardInput(8);
        assertEquals(List.of(2), simulator.snapshot().suspendedReadyQueue());
        assertEquals(List.of(1), simulator.snapshot().readyQueue());
        assertEquals(2, waiting.pendingKeyboardRequests().size());
        for (int i = 0; i < 10 && simulator.snapshot().simulatorState() != SimulatorState.FINISHED; i++) simulator.step();
        var done = simulator.snapshot(); assertEquals(List.of(7), done.screenOutput());
        assertEquals(List.of(1, 2), done.completedProcesses().stream().map(SimulatorSnapshot.CompletedProcess::processId).toList());
        for (List<?> list : List.of(done.jobs(), done.processes(), done.readyQueue(), done.suspendedReadyQueue(),
                done.pendingKeyboardRequests(), done.completedProcesses(), done.memory(), done.storage(), done.screenOutput())) {
            assertThrows(UnsupportedOperationException.class, list::clear);
        }
        simulator.reset(); var reset = simulator.snapshot();
        assertEquals(SimulatorState.CONFIGURING, reset.simulatorState());
        for (List<?> list : List.of(reset.jobs(), reset.processes(), reset.readyQueue(), reset.suspendedReadyQueue(),
                reset.pendingKeyboardRequests(), reset.completedProcesses(), reset.memory(), reset.storage(), reset.screenOutput())) assertTrue(list.isEmpty());
        assertEquals(List.of(7), done.screenOutput());
    }
    @Test void allPhysicalRowsAndCanonicalContentArePresent() {
        initialize(); submit("test.asm", "MOV AX, 7"); simulator.attemptNextAdmission();
        var snapshot = simulator.snapshot();
        assertEquals(256, snapshot.memory().size()); assertEquals(512, snapshot.storage().size());
        assertEquals(Optional.of("PCB PID=1"), snapshot.memory().getFirst().content());
        assertEquals(Optional.of("MOV AX, 7"), snapshot.memory().get(32).content());
        assertEquals("KERNEL", snapshot.memory().get(31).region()); assertEquals("USER", snapshot.memory().get(32).region());
        assertEquals(StorageRegion.FILE_INDEX, snapshot.storage().getFirst().region());
        assertEquals(StorageRegion.VIRTUAL_MEMORY, snapshot.storage().get(448).region());
        assertTrue(snapshot.storage().getFirst().content().startsWith("PROGRAM name=\"test.asm\" start="));
        assertTrue(snapshot.storage().stream().anyMatch(v -> v.region() == StorageRegion.PROGRAM_DATA && v.content().equals("MOV AX, 7")));
        assertEquals("PROGRAM name=\"a\" start=32 length=2", SimulatorSnapshotMapper.storageText(new FileIndexEntry("a", 32, 2)));
        assertEquals("MOV AX, 7", SimulatorSnapshotMapper.storageText(new StoredInstructionContent(new MovInstruction(RegisterName.AX, 7))));
    }
    @Test void userCharactersAreEscapedWithoutChangingStorageData() {
        char[] values = {'X', '\n', '\t', '\r', '\\', '\'', 0, 0xD800, 0x2028};
        String[] expected = {"'X'", "'\\n'", "'\\t'", "'\\r'", "'\\\\'", "'\\\''", "'\\u0000'", "'\\uD800'", "'\\u2028'"};
        for (int i = 0; i < values.length; i++) assertEquals(expected[i], SimulatorSnapshotMapper.storageText(new UserFileContent(values[i])));
    }
    @Test void constructorsDefensivelyCopyCallerCollections() {
        initialize(); submit("one", "INC"); simulator.start(); simulator.step();
        var value = simulator.snapshot();
        var jobs = new ArrayList<>(value.jobs()); var processes = new ArrayList<>(value.processes());
        var ready = new ArrayList<>(List.of(1)); var suspended = new ArrayList<>(List.of(2));
        var pending = new ArrayList<>(List.of(new PendingKeyboardRequest(3, 0)));
        var completed = new ArrayList<>(value.completedProcesses()); var memory = new ArrayList<>(value.memory());
        var storage = new ArrayList<>(value.storage()); var screen = new ArrayList<>(List.of(7));
        var program = new ArrayList<>(List.of(new SimulatorSnapshot.ProgramEntry(32, "INC")));
        var copy = new SimulatorSnapshot(value.simulatorState(), value.cpu(), value.currentInstruction(), value.process(),
                program, memory, value.runtimeStatus(), value.ownerPid(), value.configuration(), jobs, processes,
                ready, suspended, pending, completed, storage, screen, value.cpuTicks());
        for (List<?> input : List.of(jobs, processes, ready, suspended, pending, completed, memory, storage, screen, program)) input.clear();
        assertEquals(1, copy.jobs().size()); assertEquals(List.of(1), copy.readyQueue()); assertEquals(List.of(2), copy.suspendedReadyQueue());
        assertEquals(1, copy.pendingKeyboardRequests().size()); assertEquals(1, copy.completedProcesses().size());
        assertEquals(256, copy.memory().size()); assertEquals(512, copy.storage().size()); assertEquals(List.of(7), copy.screenOutput());
        assertEquals(1, copy.program().size());
        var stack = new ArrayList<>(List.of(9)); var files = new ArrayList<>(List.of("a"));
        var details = new SimulatorSnapshot.ProcessDetails(1, ProcessState.READY, 0, SimulatorSnapshot.Residency.RESIDENT,
                Optional.of(32), 1, 0, value.cpu().orElseThrow(), stack, files, 0, Optional.empty(), ProcessAccounting.initial());
        stack.clear(); files.clear(); assertEquals(List.of(9), details.stack()); assertEquals(List.of("a"), details.openFiles());
    }
    @Test void errorSnapshotDoesNotRepeatFailedRuntimeValidation() throws Exception {
        initialize(); submit("one", "INC"); simulator.start();
        field(simulator, "readyQueue", ReadyQueue.class).enqueue(999);
        var error = assertDoesNotThrow(simulator::snapshot);
        assertEquals(SimulatorState.ERROR, error.simulatorState()); assertTrue(error.runtimeStatus().isEmpty());
        assertEquals(256, error.memory().size()); assertEquals(512, error.storage().size());
        assertEquals(error, assertDoesNotThrow(simulator::snapshot));
    }
}
