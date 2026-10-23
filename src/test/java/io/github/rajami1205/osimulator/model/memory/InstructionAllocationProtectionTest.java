package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.process.ProcessMemoryBounds;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class InstructionAllocationProtectionTest {
    @Test void fetchRequiresActiveUserIdentityEvenWhenAnOldRangeIsReused() {
        var memory = new MainMemory(new MemoryConfiguration(128,32));
        var original = memory.allocateUser(2); var inc = new IncInstruction();
        var neighbor = memory.allocateUser(2);
        memory.writeUserBlock(neighbor, List.of(inc, inc));
        assertSame(inc, memory.readInstruction(neighbor, 34));
        memory.writeUserBlock(original,List.of(inc,inc)); assertSame(inc,memory.readInstruction(original,33));
        for(int pc: new int[]{-1,31,34,60,Integer.MAX_VALUE}) assertThrows(RuntimeException.class,()->memory.readInstruction(original,pc));
        var foreign = new MainMemory(new MemoryConfiguration(128,32)).allocateUser(2); var kernel = memory.allocateKernel(2);
        for(var invalid:List.of(foreign,kernel)) assertThrows(RuntimeException.class,()->memory.readInstruction(invalid,32));
        assertThrows(RuntimeException.class,()->memory.validateUserAllocation(original,new ProcessMemoryBounds(32,1)));
        memory.release(original); assertThrows(RuntimeException.class,()->memory.readInstruction(original,32));
        var replacement = memory.allocateUser(2); memory.writeUserBlock(replacement,List.of(new DecInstruction(),inc));
        assertEquals(original.base(),replacement.base()); assertThrows(RuntimeException.class,()->memory.readInstruction(original,32));
        assertInstanceOf(DecInstruction.class,memory.readInstruction(replacement,32));
        memory.reset(); assertThrows(RuntimeException.class,()->memory.readInstruction(replacement,32));
    }
}
