package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.execution.*;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.io.KeyboardDevice;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import io.github.rajami1205.osimulator.model.storage.exception.StorageException;
import java.util.*;
import java.time.*;
import io.github.rajami1205.osimulator.testing.ControlledClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsSecurityTest {
    private final ControlledClock realClock = new ControlledClock();
    private static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");
    private final SimulatorOrchestrator simulator = new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), realClock);
    private void init() { simulator.initialize(SimulatorConfiguration.defaults()); }
    private void submit(String name, String... lines) { simulator.submitProgram(new ProgramImage(name, new AsmParser().parse(List.of(lines)))); }
    private <T> T field(String name, Class<T> type) throws Exception {
        var f = SimulatorOrchestrator.class.getDeclaredField(name); f.setAccessible(true); return type.cast(f.get(simulator));
    }
    private ProcessControlBlock pcb(int pid) throws Exception { return field("processTable", ProcessTable.class).find(pid).orElseThrow(); }
    private ProcessResources resources(int pid) throws Exception { return field("processResources", ProcessResourceRegistry.class).find(pid).orElseThrow(); }
    private long clock() { return simulator.snapshot().cpuTicks().orElseThrow(); }
    private void assertAccounting(ProcessAccounting actual, long start, long used, Long finish) {
        assertEquals(OptionalInt.of(0), actual.cpuId()); assertEquals(Optional.of(T0.plusSeconds(start)), actual.startTime()); assertEquals(used, actual.cpuTicks());
        assertEquals(finish == null ? Optional.empty() : Optional.of(T0.plusSeconds(finish)), actual.finishTime());
    }
    @Test void weightedTicksPauseAndContextSwitchHaveExactTimesAndResetStartsFresh() throws Exception {
        assertTrue(simulator.snapshot().cpuTicks().isEmpty()); init(); assertEquals(0, clock());
        submit("one", "MOV AX, 3", "ADD AX", "INC"); submit("two", "INT 20H");
        simulator.attemptNextAdmission(); assertEquals(0, clock()); assertEquals(ProcessAccounting.initial(), pcb(1).accounting());
        simulator.start(); assertEquals(0, clock());
        field("dispatcher", Dispatcher.class).dispatch(1); assertEquals(0, clock()); assertEquals(ProcessAccounting.initial(), pcb(1).accounting());
        for (int tick=1; tick<=5; tick++) {
            simulator.step(); assertEquals(tick, clock());
            if (tick<5) assertAccounting(pcb(1).accounting(), 0, tick, null);
            if (tick==2) { simulator.pause(); realClock.advance(Duration.ofSeconds(600)); simulator.snapshot(); assertEquals(2, clock()); simulator.resume(); assertEquals(2, clock()); }
        }
        var first = simulator.completedProcesses().getFirst();
        assertEquals(Duration.ofSeconds(600), first.accounting().elapsedTime().orElseThrow()); assertAccounting(first.accounting(), 0, 5, 600L);
        simulator.step(); assertAccounting(pcb(2).accounting(), 600, 1, null); realClock.advance(Duration.ofSeconds(300)); simulator.step();
        assertAccounting(simulator.completedProcesses().getLast().accounting(), 600, 2, 900L); assertEquals(7, clock());
        simulator.reset(); assertTrue(simulator.snapshot().cpuTicks().isEmpty()); init(); assertEquals(0, clock());
        assertTrue(simulator.completedProcesses().isEmpty()); submit("fresh", "INC"); simulator.attemptNextAdmission();
        assertEquals(ProcessAccounting.initial(), pcb(1).accounting()); assertAccounting(first.accounting(), 0, 5, 600L);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void terminalKeyboardCompletionCostsZeroEvenWhenSuspended(boolean swapped) throws Exception {
        init(); submit("input", "INT 09H"); submit("other", "INC"); simulator.start(); simulator.step(); simulator.step();
        assertAccounting(pcb(1).accounting(), 0, 2, null); assertEquals(2, clock());
        if (swapped) { simulator.swapOut(1); assertEquals(2, clock()); }
        simulator.step(); assertEquals(3, clock()); assertAccounting(simulator.completedProcesses().getFirst().accounting(), 0, 1, 0L);
        assertInstanceOf(RuntimeStepResult.Idle.class, simulator.step()); assertEquals(3, clock());
        simulator.pause(); realClock.advance(Duration.ofHours(26)); simulator.submitKeyboardInput(42); assertEquals(3, clock()); assertEquals(1, simulator.pendingKeyboardRequests().size());
        simulator.resume(); assertEquals(3, clock()); assertEquals(SimulatorState.FINISHED, simulator.snapshot().simulatorState());
        assertAccounting(simulator.completedProcesses().getLast().accounting(), 0, 2, 93600L);
        assertEquals(Duration.ofHours(26), simulator.completedProcesses().getLast().accounting().elapsedTime().orElseThrow());
        assertEquals(42, simulator.completedProcesses().getLast().finalContext().dx());
    }
    @Test void engineFailureCountsNeitherFirstFailedTickNorLaterFailedFinalTick() throws Exception {
        init(); submit("bad", "INC DX"); simulator.start(); pcb(1).replaceCpuContext(pcb(1).cpuContext().withDx(new TextRegisterValue("text")));
        assertThrows(ExecutionEngineException.class, simulator::step); assertEquals(0, clock()); assertEquals(ProcessAccounting.initial(), pcb(1).accounting());
        simulator.reset(); init(); submit("overflow", "MOV AX, 32767", "LOAD AX", "ADD AX"); simulator.start();
        for(int i=0;i<5;i++) simulator.step();
        assertThrows(ExecutionEngineException.class, simulator::step); assertEquals(5, clock()); assertAccounting(pcb(1).accounting(),0,5,null);
    }
    @Test void terminalPcAndOverflowPreflightConsumeNoTick() throws Exception {
        init(); submit("one", "ADD AX"); simulator.start(); simulator.step(); pcb(1).setProgramCounter(1);
        assertThrows(ExecutionEngineException.class, simulator::step); assertEquals(1, clock()); assertEquals(1, pcb(1).accounting().cpuTicks());
        simulator.reset(); init(); submit("one", "INC"); simulator.start();
        var counter = field("tickCounter", CpuTickCounter.class); var ticks = CpuTickCounter.class.getDeclaredField("ticks"); ticks.setAccessible(true); ticks.setLong(counter, Long.MAX_VALUE);
        assertThrows(ExecutionEngineException.class, simulator::step); assertEquals(Long.MAX_VALUE, clock()); assertEquals(ProcessAccounting.initial(), pcb(1).accounting());
        assertTrue(simulator.snapshot().cpu().orElseThrow().instructionRegister().isEmpty());
        simulator.reset(); init(); submit("one", "INC"); simulator.start();
        pcb(1).replaceAccounting(new ProcessAccounting(OptionalInt.of(0),Optional.of(T0),Long.MAX_VALUE,Optional.empty()));
        assertThrows(ExecutionEngineException.class, simulator::step); assertEquals(0, clock());
        assertTrue(simulator.snapshot().cpu().orElseThrow().instructionRegister().isEmpty());
    }
    @Test void completedTickSurvivesLaterCleanupFailure() throws Exception {
        init(); submit("one", "INC"); simulator.start(); pcb(1).setNextPcbAddress(Optional.of(new PcbAddress(9)));
        assertThrows(ExecutionEngineException.class, simulator::step); assertEquals(1, clock());
        assertAccounting(pcb(1).accounting(),0,1,null); assertTrue(simulator.completedProcesses().isEmpty());
        assertEquals(SimulatorState.ERROR, simulator.snapshot().simulatorState());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void reusedStaleUserHandleNeverExecutesBeforeOrAfterDispatch(boolean alreadyRunning) throws Exception {
        init(); submit("one", "ADD AX"); simulator.start(); if(alreadyRunning) simulator.step();
        var memory = field("memory", MainMemory.class); var old = ((UserImageResidence.Resident)resources(1).residence()).allocation();
        var cpu = simulator.snapshot().cpu();
        memory.release(old); var replacement = memory.allocateUser(old.size()); memory.writeUserBlock(replacement,List.of(new IncInstruction()));
        assertEquals(old.base(),replacement.base());
        assertThrows(ExecutionEngineException.class,simulator::step); assertEquals(alreadyRunning?1:0,clock()); assertEquals(cpu,simulator.snapshot().cpu());
        assertEquals(alreadyRunning?1:0,pcb(1).accounting().cpuTicks());
        if(!alreadyRunning) { assertEquals(ProcessState.READY,pcb(1).state()); assertEquals(List.of(1),simulator.snapshot().readyQueue()); }
    }
    @Test void wrongCanonicalKernelOrMismatchedUserExtentFailsBeforeDispatch() throws Exception {
        init(); submit("one","INC"); simulator.start(); var memory = field("memory",MainMemory.class);
        memory.writePcb(resources(1).kernel(),0,new ProcessControlBlock(1,32,1));
        assertThrows(ExecutionEngineException.class,simulator::step); assertEquals(0,clock()); assertEquals(List.of(1),simulator.snapshot().readyQueue());
        simulator.reset(); init(); submit("one","INC"); simulator.start(); memory=field("memory",MainMemory.class);
        var original=resources(1); var different=memory.allocateUser(2); var registry=field("processResources",ProcessResourceRegistry.class);
        registry.remove(1); registry.register(1,original.withResidence(new UserImageResidence.Resident(different)));
        assertThrows(ExecutionEngineException.class,simulator::step); assertEquals(0,clock()); assertEquals(ProcessState.READY,pcb(1).state());
    }
    @Test void explicitAdmissionAndSwapDistinguishCorruptionFromWaitAndUserPreconditions() throws Exception {
        init(); submit("missing","INC"); field("secondaryStorage",SecondaryStorage.class).removeProgram("missing");
        var admissionError=assertThrows(ExecutionEngineException.class,simulator::attemptNextAdmission);
        assertInstanceOf(StorageException.class,admissionError.getCause()); assertEquals(SimulatorState.ERROR,simulator.snapshot().simulatorState());
        simulator.reset(); init(); submit("one","INC"); simulator.attemptNextAdmission();
        assertThrows(IllegalArgumentException.class,()->simulator.swapOut(99)); assertEquals(SimulatorState.PROGRAM_LOADED,simulator.snapshot().simulatorState());
        field("memory",MainMemory.class).release(resources(1).kernel());
        var swapError=assertThrows(ExecutionEngineException.class,()->simulator.swapOut(1));
        assertInstanceOf(MemoryProtectionException.class,swapError.getCause()); assertEquals(SimulatorState.ERROR,simulator.snapshot().simulatorState());
        simulator.reset(); simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(128,127),128,64));
        submit("large","INC","INC"); assertInstanceOf(AdmissionResult.Waiting.class,simulator.attemptNextAdmission().orElseThrow());
        assertEquals(SimulatorState.PROGRAM_LOADED,simulator.snapshot().simulatorState()); assertEquals(0,clock());
        simulator.reset(); init(); submit("one","INC"); simulator.attemptNextAdmission();
        field("secondaryStorage",SecondaryStorage.class).allocateSwap(64);
        assertInstanceOf(SwapResult.Waiting.class,simulator.swapOut(1)); assertEquals(SimulatorState.PROGRAM_LOADED,simulator.snapshot().simulatorState());
        assertEquals(0,clock());
    }
    @Test void swapInStaleSourceWhilePausedIsErrorAndValidRoundTripCostsZero() throws Exception {
        init(); submit("one","ADD AX"); submit("two","INC"); simulator.start(); simulator.step(); simulator.pause();
        assertThrows(IllegalStateException.class,()->simulator.swapOut(1)); assertEquals(SimulatorState.PAUSED,simulator.snapshot().simulatorState());
        simulator.swapOut(2); simulator.swapIn(2); assertEquals(1,clock()); simulator.swapOut(2);
        var handle=((UserImageResidence.Suspended)resources(2).residence()).allocation(); field("secondaryStorage",SecondaryStorage.class).releaseSwap(handle);
        var error=assertThrows(ExecutionEngineException.class,()->simulator.swapIn(2)); assertInstanceOf(StorageException.class,error.getCause());
        assertEquals(SimulatorState.ERROR,simulator.snapshot().simulatorState()); assertEquals(1,clock());
    }
    @Test void swapInCapacityWaitAndAdmissionIntegrityTaxonomyRemainDistinct() throws Exception {
        init(); submit("one","INC"); simulator.attemptNextAdmission(); simulator.swapOut(1);
        field("memory",MainMemory.class).allocateUser(224);
        assertInstanceOf(SwapResult.Waiting.class,simulator.swapIn(1));
        assertEquals(SimulatorState.PROGRAM_LOADED,simulator.snapshot().simulatorState()); assertEquals(0,clock());
        simulator.reset(); init(); submit("one","INC"); simulator.attemptNextAdmission(); submit("two","INC");
        var admission=field("processAdmissionService",ProcessAdmissionService.class);
        var sequence=ProcessAdmissionService.class.getDeclaredField("nextProcessId"); sequence.setAccessible(true); sequence.setLong(admission,1);
        var error=assertThrows(ExecutionEngineException.class,simulator::attemptNextAdmission);
        assertInstanceOf(IllegalStateException.class,error.getCause()); assertEquals(SimulatorState.ERROR,simulator.snapshot().simulatorState());
        simulator.reset();
        assertThrows(IllegalStateException.class,simulator::attemptNextAdmission);
        assertEquals(SimulatorState.CONFIGURING,simulator.snapshot().simulatorState());
    }
    @Test void keyboardLifecycleRejectsBeforeMutationAndRetainsPrequeue() throws Exception {
        assertThrows(IllegalStateException.class,()->simulator.submitKeyboardInput(1)); init(); simulator.submitKeyboardInput(3);
        submit("input","INT 09H","INT 09H"); simulator.submitKeyboardInput(4); simulator.start();
        for(int i=0;i<4;i++) simulator.step(); assertEquals(4,simulator.completedProcesses().getFirst().finalContext().dx());
        var keyboard=field("keyboard",KeyboardDevice.class); assertFalse(keyboard.hasInput()); var finished=simulator.snapshot();
        assertThrows(IllegalStateException.class,()->simulator.submitKeyboardInput(5)); assertFalse(keyboard.hasInput()); assertEquals(finished,simulator.snapshot());
        simulator.reset(); init(); submit("bad","POP AX"); simulator.start();
        while(simulator.snapshot().simulatorState()!=SimulatorState.ERROR) { try { simulator.step(); } catch(ExecutionEngineException expected) { } }
        var error=simulator.snapshot(); keyboard=field("keyboard",KeyboardDevice.class);
        assertThrows(IllegalStateException.class,()->simulator.submitKeyboardInput(6)); assertFalse(keyboard.hasInput()); assertEquals(error,simulator.snapshot());
    }
}
