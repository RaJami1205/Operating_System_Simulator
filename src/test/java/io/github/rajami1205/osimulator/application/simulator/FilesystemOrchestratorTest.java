package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FilesystemOrchestratorTest {
    private final SimulatorOrchestrator simulator=new SimulatorOrchestrator(new ProgramLoader(),new ExecutionEngine());
    private void load(List<String> program) {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256,32),512,64));
        simulator.loadProgram(new AsmParser().parse(program));simulator.start();
    }
    @Test void completeAsmWorkflowAndCanonicalSnapshotsPreserveTextAndResetStorage() {
        var program=List.of("MOV DX, \"notes.txt\"","mov ah, 3ch","INT 21H","MOV AH, 3DH","INT 21H",
                "MOV AL, \"hello; A,B\"","MOV AH, 40H","INT 21H","MOV AH, 4DH","INT 21H","MOV AH, 41H","INT 21H");
        load(program);
        var parsed=new AsmParser().parse(program);
        for(int i=0;i<parsed.size();i++) {
            for(int tick=0;tick<parsed.get(i).executionWeight().ticks();tick++) simulator.step();
            var snapshot=simulator.snapshot();
            assertEquals("notes.txt",snapshot.cpu().orElseThrow().dxValue().textValue());
            String expected=i==1?"MOV AH, 3CH":program.get(i);
            if (snapshot.ownerPid().isPresent()) assertEquals(expected,snapshot.currentInstruction().orElseThrow().semanticInstruction());
            else assertTrue(snapshot.currentInstruction().isEmpty());
            assertEquals(java.util.Optional.of(expected),snapshot.cpu().orElseThrow().instructionRegister());
            if (i < parsed.size()-1) assertEquals(expected,snapshot.program().get(i).instruction());
            else assertTrue(snapshot.program().isEmpty());
        }
        assertEquals(SimulatorState.FINISHED,simulator.snapshot().simulatorState());
        simulator.reset();
        load(List.of("MOV DX, \"kept\"","MOV AH, 3CH","INT 21H"));
        for(int tick=0;tick<7;tick++) simulator.step();
        simulator.reset();
        load(List.of("MOV DX, \"kept\"","MOV AH, 3DH","INT 21H"));
        for(int tick=0;tick<6;tick++) simulator.step();
        assertThrows(ExecutionEngineException.class,simulator::step);
        assertEquals(SimulatorState.ERROR,simulator.snapshot().simulatorState());
    }
}
