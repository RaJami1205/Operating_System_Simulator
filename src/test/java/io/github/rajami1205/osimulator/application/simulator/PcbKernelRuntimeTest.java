package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.cpu.CpuRegisters;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PcbKernelRuntimeTest {
    private final SimulatorOrchestrator simulator = new SimulatorOrchestrator(new ProgramLoader(),new ExecutionEngine());
    private void submit(String name, String... source) {
        simulator.submitProgram(new ProgramImage(name,new AsmParser().parse(List.of(source))));
    }
    private <T> T field(String name,Class<T> type) {
        try {var f=SimulatorOrchestrator.class.getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(simulator));}
        catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
    }
    private ProcessControlBlock pcb(int pid) {return field("processTable",ProcessTable.class).find(pid).orElseThrow();}
    private ProcessResources resource(int pid) {return field("processResources",ProcessResourceRegistry.class).find(pid).orElseThrow();}
    private PcbValue value(int pid,PcbField key) {
        return ((PcbContent)field("memory",MainMemory.class).read(resource(pid).kernel().base()+key.offset())).value();
    }
    private void assertPublished(int pid) {
        var pcb=pcb(pid);var memory=field("memory",MainMemory.class);var image=PcbMemoryImage.capture(pcb);
        for(var cell:image.cells()) assertEquals(cell,memory.read(resource(pid).kernel().base()+cell.field().offset()));
        if (simulator.snapshot().ownerPid().equals(Optional.of(pid))) assertEquals(field("cpu",CpuRegisters.class).snapshot(),pcb.cpuContext());
    }

    @Test void minimumKernelAdmitsFiveCompletePcbBlocks() {
        simulator.initialize(SimulatorConfiguration.defaults());
        for (int pid = 1; pid <= 5; pid++) submit("p" + pid, "INC");
        simulator.start();
        assertEquals(5, field("processTable", ProcessTable.class).size());
        for (int pid = 1; pid <= 5; pid++) {
            var allocation = resource(pid).kernel();
            assertEquals((pid - 1) * 28, allocation.base());
            assertEquals(28, allocation.size());
            assertPublished(pid);
        }
    }

    @Test void successfulWeightedTicksPublishContextStackFlagsAndAccountingWithoutEarlyEffects() {
        simulator.initialize(SimulatorConfiguration.defaults());
        submit("p","MOV AX, 7","ADD AX","CMP AX, AX","PARAM 1, 2, 3","PUSH AX","POP BX","INT 09H","INC");
        simulator.start();simulator.step();assertPublished(1);
        assertEquals(new PcbValue.Number(7),value(1,PcbField.AX));
        for(int i=0;i<2;i++) {
            simulator.step();assertPublished(1);
            assertEquals(new PcbValue.Number(141),value(1,PcbField.PC));
            assertEquals(new PcbValue.Number(0),value(1,PcbField.AC));
        }
        simulator.step();assertPublished(1);assertEquals(new PcbValue.Number(7),value(1,PcbField.AC));
        for(int i=0;i<30 && !simulator.waitingForInput();i++) {simulator.step();assertPublished(1);}
        assertTrue(simulator.waitingForInput());
        assertEquals(new PcbValue.Flag(true),value(1,PcbField.EQUAL));
        assertEquals(new PcbValue.Number(7),value(1,PcbField.BX));
        assertEquals(new PcbValue.Number(2),value(1,PcbField.STACK_TOP));
        assertEquals(PcbValue.Empty.INSTANCE,value(1,PcbField.STACK_3));
        assertEquals(new PcbValue.Number(pcb(1).accounting().cpuTicks()),value(1,PcbField.CPU_TICKS));
        assertInstanceOf(PcbValue.Timestamp.class,value(1,PcbField.START));
        simulator.submitKeyboardInput(42);assertPublished(1);
        assertEquals(42,pcb(1).cpuContext().dx());
        assertEquals(new PcbValue.Number(147),value(1,PcbField.PC));
    }

    @Test void relativeBranchPublishesPhysicalTargetAndOccupiedKernelLimitsAdmission() {
        simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140),512,64));
        field("memory",MainMemory.class).allocateKernel(112); // Reserve four blocks to exercise actual capacity.
        var source=new ArrayList<>(Collections.nCopies(16,"INC"));source.add("JNE -4");source.add("INT 20H");
        submit("branch",source.toArray(String[]::new));submit("waiting","INC");simulator.start();
        assertEquals(1,field("processTable",ProcessTable.class).size());
        for(int i=0;i<16;i++) simulator.step();
        assertEquals(156,pcb(1).programCounter());
        for(int i=0;i<10 && pcb(1).programCounter()==156;i++) {simulator.step();assertPublished(1);}
        assertEquals(new PcbValue.Number(152),value(1,PcbField.PC));
        assertEquals(152,simulator.snapshot().cpu().orElseThrow().programCounter());
    }

    @Test void contextSwitchKeyboardAndRelocationKeepKernelIdentityAndCapturedValues() {
        simulator.initialize(SimulatorConfiguration.defaults());
        submit("input","MOV AX, 9","INT 09H","INC");submit("other","ADD AX","INC");simulator.start();
        for(int i=0;i<3;i++) simulator.step();
        var kernel=resource(1).kernel();var context=pcb(1).cpuContext();assertPublished(1);
        simulator.step();assertPublished(2);
        assertEquals(context,pcb(1).cpuContext());
        simulator.swapOut(1);assertPublished(1);
        assertEquals(PcbValue.Empty.INSTANCE,value(1,PcbField.BASE));
        simulator.submitKeyboardInput(25);assertPublished(1);assertPublished(2);
        field("memory",MainMemory.class).allocateUser(3);
        simulator.swapIn(1);assertPublished(1);
        assertEquals(kernel,resource(1).kernel());
        assertEquals(pcb(1).programStartAddress()+2,pcb(1).programCounter());
        assertEquals(25,pcb(1).cpuContext().dx());
    }
    @Test void fileOperationsPublishOpenNamesAndFailureDoesNotPublishPartialStackImage() {
        simulator.initialize(SimulatorConfiguration.defaults());
        var source=List.of("MOV DX, \"notes\"", "MOV AH, 3CH", "INT 21H", "MOV AH, 3DH", "INT 21H",
                "MOV AH, 41H", "INT 21H", "INC");
        submit("files",source.toArray(String[]::new));simulator.start();
        var instructions=new AsmParser().parse(source);
        for(int index=0;index<instructions.size()-1;index++) {
            for(int tick=0;tick<instructions.get(index).executionWeight().ticks();tick++) {simulator.step();assertPublished(1);}
            if(index==4) assertEquals(new PcbValue.Names(List.of("notes")),value(1,PcbField.OPEN_FILES));
            if(index==6) assertEquals(new PcbValue.Names(List.of()),value(1,PcbField.OPEN_FILES));
        }
        simulator.reset();simulator.initialize(SimulatorConfiguration.defaults());
        submit("overflow","PARAM 1, 2, 3","PARAM 4, 5, 6","INC");simulator.start();
        for(int tick=0;tick<5;tick++) simulator.step();
        assertPublished(1);
        var memory=field("memory",MainMemory.class);
        var before=java.util.stream.IntStream.range(0,28).mapToObj(memory::cell).toList();
        var accounting=pcb(1).accounting();var context=pcb(1).cpuContext();
        assertThrows(RuntimeException.class,simulator::step);
        assertEquals(before,java.util.stream.IntStream.range(0,28).mapToObj(memory::cell).toList());
        assertEquals(context,pcb(1).cpuContext());assertEquals(accounting,pcb(1).accounting());
    }

}
