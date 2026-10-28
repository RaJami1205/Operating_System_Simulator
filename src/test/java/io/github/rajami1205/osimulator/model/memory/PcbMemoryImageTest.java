package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.cpu.*;
import java.util.List;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PcbMemoryImageTest {
    @Test void exactStableLayoutAndImmutableTypedCapture() {
        assertEquals("PID STATE PC IR AC AX BX CX DX AH AL EQUAL OVERFLOW BASE LIMIT PRIORITY CPU_ID START CPU_TICKS FINISH OPEN_FILES STACK_TOP STACK_0 STACK_1 STACK_2 STACK_3 STACK_4 NEXT_PCB",
                String.join(" ", Arrays.stream(PcbField.values()).map(Enum::name).toList()));
        assertEquals(28, PcbField.PCB_SIZE);
        var pcb = new ProcessControlBlock(1,140,10);
        pcb.openFiles().open("z"); pcb.openFiles().open("a"); pcb.stack().push(7);
        pcb.replaceCpuContext(pcb.cpuContext().withDx(new TextRegisterValue("text")));
        var image = PcbMemoryImage.capture(pcb);
        assertEquals(28, image.cells().size());
        for (int i=0;i<28;i++) {
            assertEquals(i, image.cells().get(i).field().offset());
            assertTrue(image.cells().get(i).belongsTo(pcb));
        }
        assertEquals(new PcbValue.Names(List.of("a","z")), value(image,PcbField.OPEN_FILES));
        assertEquals(new PcbValue.Register(new TextRegisterValue("text")), value(image,PcbField.DX));
        assertEquals(new PcbValue.Number(0), value(image,PcbField.STACK_TOP));
        assertEquals(new PcbValue.Number(7), value(image,PcbField.STACK_0));
        for (var field : List.of(PcbField.IR,PcbField.CPU_ID,PcbField.START,PcbField.FINISH,PcbField.NEXT_PCB,PcbField.STACK_1,PcbField.STACK_4))
            assertEquals(PcbValue.Empty.INSTANCE,value(image,field));
        assertThrows(UnsupportedOperationException.class,()->image.cells().clear());
        assertThrows(UnsupportedOperationException.class,()->((PcbValue.Names)value(image,PcbField.OPEN_FILES)).values().clear());
        pcb.stack().pop(); pcb.openFiles().close("a"); pcb.setProgramCounter(4097);
        assertEquals(new PcbValue.Number(4096),value(image,PcbField.PC));
        var updated=PcbMemoryImage.capture(pcb);
        assertEquals(new PcbValue.Number(-1),value(updated,PcbField.STACK_TOP));
        assertEquals(PcbValue.Empty.INSTANCE,value(updated,PcbField.STACK_0));
        pcb.changeState(ProcessState.READY_SUSPENDED);
        assertEquals(PcbValue.Empty.INSTANCE,value(PcbMemoryImage.capture(pcb),PcbField.BASE));
    }

    @Test void wholeBlockWritesRejectWrongOwnersSizesStaleHandlesAndPreserveNeighbors() {
        var memory=new MainMemory(new MemoryConfiguration(256,140));
        var a=memory.allocateKernel(28); var b=memory.allocateKernel(28);
        var first=new ProcessControlBlock(1,140,10); var second=new ProcessControlBlock(2,150,10);
        memory.materializePcb(a,first);memory.materializePcb(b,second);
        var before=java.util.stream.IntStream.range(0,56).mapToObj(memory::cell).toList();
        assertThrows(RuntimeException.class,()->memory.writePcbImage(a,first,PcbMemoryImage.capture(second)));
        assertThrows(RuntimeException.class,()->memory.validatePcbAllocation(a,new ProcessControlBlock(1,140,10)));
        assertThrows(RuntimeException.class,()->memory.materializePcb(memory.allocateKernel(1),first));
        assertEquals(before,java.util.stream.IntStream.range(0,56).mapToObj(memory::cell).toList());
        memory.release(a);var reuse=memory.allocateKernel(28);
        assertNotEquals(a.allocationId(),reuse.allocationId());
        assertThrows(RuntimeException.class,()->memory.writePcbImage(a,first,PcbMemoryImage.capture(first)));
        memory.materializePcb(reuse,first);
        assertEquals(before,java.util.stream.IntStream.range(0,56).mapToObj(memory::cell).toList());
    }

    private PcbValue value(PcbMemoryImage image,PcbField field) {return image.cells().get(field.offset()).value();}
}
