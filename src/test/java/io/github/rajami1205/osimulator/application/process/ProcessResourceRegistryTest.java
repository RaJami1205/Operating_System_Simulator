package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProcessResourceRegistryTest {
    @Test void validatesTypedResourcesAndProtectsCanonicalPublication() {
        var memory=new MainMemory(new MemoryConfiguration(128,32));
        var kernel=memory.allocateKernel(io.github.rajami1205.osimulator.model.memory.PcbField.PCB_SIZE);var user=memory.allocateUser(2);
        var resources=new ProcessResources(kernel,new PcbAddress(kernel.base()),new UserImageResidence.Resident(user));
        var registry=new ProcessResourceRegistry();registry.register(1,resources);
        assertSame(resources,registry.find(1).orElseThrow());
        assertThrows(IllegalStateException.class,()->registry.register(1,resources));
        assertThrows(IllegalArgumentException.class,()->registry.register(0,resources));
        assertThrows(UnsupportedOperationException.class,()->registry.entries().clear());
        assertThrows(IllegalArgumentException.class,()->new UserImageResidence.Resident(kernel));
        assertThrows(IllegalArgumentException.class,()->new ProcessResources(user,new PcbAddress(user.base()),resources.residence()));
        assertThrows(IllegalArgumentException.class,()->new ProcessResources(kernel,new PcbAddress(5),resources.residence()));
        var other=resources.withResidence(resources.residence());
        assertThrows(IllegalStateException.class,()->registry.replace(1,other,resources));
        assertSame(resources,registry.find(1).orElseThrow());
    }
    @Test void relocationRequiresSuspensionAndPreservesExtentAndLogicalTerminalPc() {
        var pcb=new ProcessControlBlock(1,32,3);pcb.setProgramCounter(4099);
        assertThrows(IllegalStateException.class,()->pcb.relocateSuspended(new ProcessMemoryBounds(40,3)));
        pcb.changeState(ProcessState.BLOCKED_SUSPENDED);
        assertThrows(IllegalStateException.class,()->pcb.relocateSuspended(new ProcessMemoryBounds(40,2)));
        pcb.relocateSuspended(new ProcessMemoryBounds(40,3));
        assertThrows(IllegalStateException.class,pcb::memoryBounds);assertEquals(3,pcb.instructionCount());
        pcb.changeState(ProcessState.BLOCKED);assertEquals(new ProcessMemoryBounds(40,3),pcb.memoryBounds());
        assertEquals(4099,pcb.programCounter());
    }
}
