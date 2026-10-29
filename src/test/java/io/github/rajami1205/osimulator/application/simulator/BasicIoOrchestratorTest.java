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
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140),512,64));
        simulator.loadProgram(new AsmParser().parse(List.of(source))); simulator.start();
    }
    private void state(SimulatorState lifecycle,String process,int pc,int dx) {
        var snapshot=simulator.snapshot(); assertEquals(lifecycle,snapshot.simulatorState());
        if (process.equals("TERMINATED")) {
            assertTrue(snapshot.process().isEmpty());
            var context=simulator.completedProcesses().getFirst().finalContext();
            assertEquals(4096 + pc,context.programCounter());assertEquals(dx,context.dx());
        } else if (process.equals("RUNNING")) {
            assertEquals(process,snapshot.process().orElseThrow().processState());
            assertEquals(4096 + pc,snapshot.cpu().orElseThrow().programCounter());
            assertEquals(dx,snapshot.cpu().orElseThrow().dx());
        } else {
            assertTrue(snapshot.process().isEmpty());
            try {
                var field=SimulatorOrchestrator.class.getDeclaredField("processTable");field.setAccessible(true);
                var table=(io.github.rajami1205.osimulator.model.process.ProcessTable)field.get(simulator);
                var pcb=table.find(1).orElseThrow();
                assertEquals(process,pcb.state().name());assertEquals(4096 + pc,pcb.programCounter());
                assertEquals(dx,pcb.cpuContext().dx());
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }
    @Test void fifoScreenFormattingAndResetRemainSessionOwned() {
        load("MOV DX, 0","int 10h","MOV DX, 255","INT 10H");
        for(int instruction=0;instruction<4;instruction++) {
            var before=simulator.snapshot();
            simulator.step();
            String expected=List.of("MOV DX, 0","INT 10H","MOV DX, 255","INT 10H").get(instruction);
            var snapshot=simulator.snapshot();
            if (instruction < 3) assertEquals(expected,snapshot.currentInstruction().orElseThrow().semanticInstruction());
            else assertTrue(snapshot.currentInstruction().isEmpty());
            assertEquals(Optional.of(expected),snapshot.cpu().orElseThrow().instructionRegister());
            if (instruction < 3) assertEquals(expected,snapshot.program().get(instruction).instruction());
            assertEquals(Optional.of(expected),before.memory().get(140+instruction).content());
        }
        state(SimulatorState.FINISHED,"TERMINATED",4,255);
        var saved=simulator.screenOutput(); assertEquals(List.of(0,255),saved);
        assertThrows(UnsupportedOperationException.class,()->saved.clear());
        simulator.reset(); assertTrue(simulator.screenOutput().isEmpty()); assertEquals(List.of(0,255),saved);
    }
    @Test void explicitTerminationFinishesSameStepWithoutResetOrLaterExecution() {
        load("INT 20H","INC");
        simulator.step(); state(SimulatorState.FINISHED,"TERMINATED",0,0);
        assertTrue(simulator.snapshot().currentInstruction().isEmpty());
        assertEquals(Optional.of("INT 20H"),simulator.snapshot().cpu().orElseThrow().instructionRegister());
        assertTrue(simulator.configuration().isPresent()); assertTrue(simulator.snapshot().program().isEmpty());
        assertEquals(0,simulator.snapshot().cpu().orElseThrow().accumulator());
        assertThrows(IllegalStateException.class,simulator::step);
    }
}
