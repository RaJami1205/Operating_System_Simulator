package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BasicIoOrchestratorTest {
    private final SimulatorOrchestrator simulator=new SimulatorOrchestrator(new ProgramLoader(),new ExecutionEngine());
    private void load(String... source) {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(128,32),512,64));
        simulator.loadProgram(new AsmParser().parse(List.of(source))); simulator.start();
    }
    private void twoTicks() { simulator.step(); simulator.step(); }
    private void state(SimulatorState lifecycle,String process,int pc,int dx) {
        var snapshot=simulator.snapshot(); assertEquals(lifecycle,snapshot.simulatorState());
        if (process.equals("TERMINATED")) {
            assertTrue(snapshot.process().isEmpty());
            var context=simulator.completedProcesses().getFirst().finalContext();
            assertEquals(pc,context.programCounter());assertEquals(dx,context.dx());
        } else if (process.equals("RUNNING")) {
            assertEquals(process,snapshot.process().orElseThrow().processState());
            assertEquals(pc,snapshot.cpu().orElseThrow().programCounter());
            assertEquals(dx,snapshot.cpu().orElseThrow().dx());
        } else {
            assertTrue(snapshot.process().isEmpty());
            try {
                var field=SimulatorOrchestrator.class.getDeclaredField("processTable");field.setAccessible(true);
                var table=(io.github.rajami1205.osimulator.model.process.ProcessTable)field.get(simulator);
                var pcb=table.find(1).orElseThrow();
                assertEquals(process,pcb.state().name());assertEquals(pc,pcb.programCounter());
                assertEquals(dx,pcb.cpuContext().dx());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }
    @Test void runningSubmissionCompletesWaitAndInvalidSubmissionDoesNotChangeRuntime() {
        load("INT 09H","INC"); twoTicks(); state(SimulatorState.RUNNING,"BLOCKED",0,0);
        var before=simulator.snapshot();
        for(int value:new int[]{-1,256}) assertThrows(IllegalArgumentException.class,()->simulator.submitKeyboardInput(value));
        for(int i=0;i<5;i++) simulator.step();
        assertEquals(before,simulator.snapshot()); assertTrue(simulator.waitingForInput());
        simulator.submitKeyboardInput(255); state(SimulatorState.RUNNING,"READY",1,255);
        assertFalse(simulator.waitingForInput());
        simulator.step(); state(SimulatorState.FINISHED,"TERMINATED",2,255);
    }
    @Test void pausedSubmissionQueuesUntilResumeAndCanFinishThere() {
        load("INT 09H"); twoTicks(); simulator.pause();
        simulator.submitKeyboardInput(42); state(SimulatorState.PAUSED,"BLOCKED",0,0);
        assertTrue(simulator.waitingForInput());
        simulator.resume(); state(SimulatorState.FINISHED,"TERMINATED",1,42);
        assertFalse(simulator.waitingForInput());
    }
    @Test void resumeWithoutInputKeepsWaitAndResetDiscardsPendingAndQueuedInput() {
        load("INT 09H"); twoTicks(); simulator.pause(); simulator.resume();
        state(SimulatorState.RUNNING,"BLOCKED",0,0);
        simulator.pause(); simulator.submitKeyboardInput(99); simulator.reset();
        assertFalse(simulator.waitingForInput()); assertTrue(simulator.screenOutput().isEmpty());
        assertThrows(IllegalStateException.class,()->simulator.submitKeyboardInput(1));
        load("INT 09H"); twoTicks(); state(SimulatorState.RUNNING,"BLOCKED",0,0);
        simulator.submitKeyboardInput(0); state(SimulatorState.FINISHED,"TERMINATED",1,0);
    }
    @Test void fifoScreenFormattingAndResetRemainSessionOwned() {
        load("int 09h","int 10h","INT 09H","INT 10H");
        simulator.submitKeyboardInput(0); simulator.submitKeyboardInput(255);
        for(int instruction=0;instruction<4;instruction++) {
            simulator.step();
            String expected=instruction%2==0?"INT 09H":"INT 10H";
            var snapshot=simulator.snapshot();
            assertEquals(expected,snapshot.currentInstruction().orElseThrow().semanticInstruction());
            assertEquals(Optional.of(expected),snapshot.cpu().orElseThrow().instructionRegister());
            assertEquals(expected,snapshot.program().get(instruction).instruction());
            assertEquals(Optional.of(expected),snapshot.memory().get(32+instruction).content());
            simulator.step(); assertFalse(simulator.waitingForInput());
        }
        state(SimulatorState.FINISHED,"TERMINATED",4,255);
        var saved=simulator.screenOutput(); assertEquals(List.of(0,255),saved);
        assertThrows(UnsupportedOperationException.class,()->saved.clear());
        simulator.reset(); assertTrue(simulator.screenOutput().isEmpty()); assertEquals(List.of(0,255),saved);
    }
    @Test void explicitTerminationFinishesSameStepWithoutResetOrLaterExecution() {
        load("INT 20H","INC"); simulator.step(); state(SimulatorState.RUNNING,"RUNNING",0,0);
        simulator.step(); state(SimulatorState.FINISHED,"TERMINATED",0,0);
        assertTrue(simulator.snapshot().currentInstruction().isEmpty());
        assertEquals(Optional.of("INT 20H"),simulator.snapshot().cpu().orElseThrow().instructionRegister());
        assertTrue(simulator.configuration().isPresent()); assertTrue(simulator.snapshot().program().isEmpty());
        assertEquals(0,simulator.snapshot().cpu().orElseThrow().accumulator());
        assertThrows(IllegalStateException.class,simulator::step);
    }
}
