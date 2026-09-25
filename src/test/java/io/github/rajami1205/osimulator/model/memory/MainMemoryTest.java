package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.cpu.RegisterName;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.memory.exception.*;
import io.github.rajami1205.osimulator.model.process.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class MainMemoryTest {
    private final Instruction instruction = new LoadInstruction(RegisterName.AX);
    private MainMemory memory() { return new MainMemory(new MemoryConfiguration(128, 32)); }

    @Test
    void exposesConfigurationAndExplicitEmptyCellViews() {
        var configuration = new MemoryConfiguration(128, 32);
        var memory = new MainMemory(configuration);
        assertSame(configuration, memory.configuration());
        assertEquals(128, memory.size());
        for (int i = 0; i < memory.size(); i++) {
            assertSame(EmptyContent.INSTANCE, memory.read(i));
            assertTrue(memory.isEmpty(i));
            var region = i < 32 ? MemoryRegion.KERNEL : MemoryRegion.USER;
            assertEquals(region, memory.regionOf(i));
            assertEquals(new MemoryCell(i, region, EmptyContent.INSTANCE), memory.cell(i));
        }
        assertThrows(NullPointerException.class, () -> new MainMemory(null));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 128, Integer.MAX_VALUE})
    void rejectsInvalidPhysicalAddresses(int address) {
        var memory = memory();
        assertThrows(InvalidMemoryAddressException.class, () -> memory.read(address));
        assertThrows(InvalidMemoryAddressException.class, () -> memory.cell(address));
        assertThrows(InvalidMemoryAddressException.class, () -> memory.isEmpty(address));
        assertThrows(InvalidMemoryAddressException.class, () -> memory.regionOf(address));
    }

    @Test
    void validatesContentAndCellValues() {
        assertThrows(NullPointerException.class, () -> new InstructionContent(null));
        assertThrows(NullPointerException.class, () -> new PcbContent(null));
        assertThrows(IllegalArgumentException.class, () -> new MemoryCell(-1, MemoryRegion.USER, EmptyContent.INSTANCE));
        assertThrows(NullPointerException.class, () -> new MemoryCell(0, null, EmptyContent.INSTANCE));
        assertThrows(NullPointerException.class, () -> new MemoryCell(0, MemoryRegion.KERNEL, null));
        assertEquals(new InstructionContent(instruction), new InstructionContent(instruction));
    }

    @Test
    void storesCanonicalPcbAndInstructionsInSeparateBoundedRegions() {
        var memory = memory();
        var kernel = memory.allocateKernel(32);
        var user = memory.allocateUser(96);
        var pcb = new ProcessControlBlock(1, user.base(), user.size());
        memory.writePcb(kernel, 31, pcb);
        memory.writeInstruction(user, 95, instruction);
        assertEquals(0, kernel.base());
        assertEquals(32, kernel.endExclusive());
        assertEquals(32, user.base());
        assertEquals(128, user.endExclusive());
        assertSame(pcb, assertInstanceOf(PcbContent.class, memory.read(31)).pcb());
        pcb.changeState(ProcessState.READY);
        assertEquals(ProcessState.READY, assertInstanceOf(PcbContent.class, memory.read(31)).pcb().state());
        assertSame(instruction, assertInstanceOf(InstructionContent.class, memory.read(127)).instruction());
        assertThrows(MemoryAllocationException.class, () -> memory.allocateUser(1));
        assertThrows(MemoryAllocationException.class, () -> memory.allocateKernel(1));
        assertThrows(MemoryProtectionException.class, () -> memory.writeInstruction(kernel, 0, instruction));
        assertThrows(MemoryProtectionException.class, () -> memory.writeUserBlock(kernel, List.of(instruction)));
        assertThrows(MemoryProtectionException.class, () -> memory.writeUserBlock(kernel, List.of()));
        assertThrows(MemoryProtectionException.class, () -> memory.writePcb(user, 0, pcb));
    }

    @Test
    void emptyContentIsNotAvailableAllocationSpace() {
        var memory = memory();
        var all = memory.allocateUser(96);
        assertTrue(memory.isEmpty(32));
        assertThrows(MemoryAllocationException.class, () -> memory.allocateUser(1));
        memory.release(all);
        assertEquals(32, memory.allocateUser(96).base());
    }

    @Test
    void typedWritesRejectNullOutOfBoundsForeignAndStaleHandlesAtomically() {
        var memory = memory();
        var user = memory.allocateUser(2);
        memory.writeInstruction(user, 0, instruction);
        var before = memory.cell(32);
        assertThrows(NullPointerException.class, () -> memory.writeInstruction(user, 0, null));
        for (int offset : new int[]{-1, 2, Integer.MAX_VALUE}) {
            assertThrows(InvalidMemoryAddressException.class, () -> memory.writeInstruction(user, offset, instruction));
        }
        var foreign = memory().allocateUser(2);
        assertThrows(MemoryProtectionException.class, () -> memory.writeInstruction(foreign, 0, instruction));
        assertThrows(InvalidMemoryReleaseException.class, () -> memory.release(foreign));
        assertEquals(before, memory.cell(32));
        memory.release(user);
        assertSame(EmptyContent.INSTANCE, memory.read(32));
        var reused = memory.allocateUser(2);
        memory.writeInstruction(reused, 0, instruction);
        assertThrows(InvalidMemoryReleaseException.class, () -> memory.release(user));
        assertThrows(MemoryProtectionException.class, () -> memory.writeUserBlock(user, List.of(instruction)));
        assertEquals(before, memory.cell(32));
        var kernel = memory.allocateKernel(1);
        var pcb = new ProcessControlBlock(1, 32, 2);
        memory.writePcb(kernel, 0, pcb);
        assertThrows(NullPointerException.class, () -> memory.writePcb(kernel, 0, null));
        assertThrows(InvalidMemoryAddressException.class, () -> memory.writePcb(kernel, 1, pcb));
        assertSame(pcb, assertInstanceOf(PcbContent.class, memory.read(0)).pcb());
    }

    @Test
    void blockWritesCopyValidateAndCommitAtomically() {
        var memory = memory();
        var allocation = memory.allocateUser(2);
        var original = new MovInstruction(RegisterName.BX, 7);
        memory.writeUserBlock(allocation, List.of(original, original));
        assertThrows(NullPointerException.class, () -> memory.writeUserBlock(allocation, null));
        assertThrows(NullPointerException.class, () -> memory.writeUserBlock(allocation, Arrays.asList(instruction, null)));
        assertThrows(InvalidMemoryAddressException.class, () -> memory.writeUserBlock(allocation, List.of(instruction, instruction, instruction)));
        assertEquals(new InstructionContent(original), memory.read(32));
        assertEquals(new InstructionContent(original), memory.read(33));
        memory.writeUserBlock(allocation, List.of());
        assertEquals(new InstructionContent(original), memory.read(32));
        var mutable = new ArrayList<Instruction>(List.of(instruction, original));
        memory.writeUserBlock(allocation, mutable);
        mutable.clear();
        assertEquals(new InstructionContent(instruction), memory.read(32));
        assertEquals(new InstructionContent(original), memory.read(33));
        assertTrue(memory.isEmpty(34));
    }

    @Test
    void translatesOnlyFetchableAddressesWithinOneActiveUserAllocation() {
        var memory = memory();
        var a = memory.allocateUser(2);
        var b = memory.allocateUser(1);
        memory.writeUserBlock(a, List.of(instruction, instruction));
        var other = new StoreInstruction(RegisterName.DX);
        memory.writeInstruction(b, 0, other);
        var bounds = new ProcessMemoryBounds(a.base(), a.size());
        assertEquals(32, memory.physicalAddress(bounds, 0));
        assertEquals(33, memory.physicalAddress(bounds, 1));
        assertSame(instruction, memory.readInstruction(bounds, 1));
        for (int pc : new int[]{-1, 2, Integer.MAX_VALUE}) {
            assertThrows(MemoryProtectionException.class, () -> memory.readInstruction(bounds, pc));
        }
        for (var invalid : List.of(new ProcessMemoryBounds(0, 1), new ProcessMemoryBounds(31, 2),
                new ProcessMemoryBounds(127, 2), new ProcessMemoryBounds(32, 3), new ProcessMemoryBounds(33, 1))) {
            assertThrows(MemoryProtectionException.class, () -> memory.readInstruction(invalid, 0));
        }
        memory.release(a);
        assertThrows(MemoryProtectionException.class, () -> memory.readInstruction(bounds, 0));
        assertSame(other, memory.readInstruction(new ProcessMemoryBounds(b.base(), b.size()), 0));
        var empty = memory.allocateUser(2);
        assertThrows(MemoryProtectionException.class, () -> memory.readInstruction(new ProcessMemoryBounds(empty.base(), empty.size()), 0));
    }

    @Test
    void clearUserAndResetKeepContentAndAllocationsConsistent() {
        var memory = memory();
        var user = memory.allocateUser(96);
        var kernel = memory.allocateKernel(32);
        var pcb = new ProcessControlBlock(1, 32, 96);
        memory.writePcb(kernel, 0, pcb);
        memory.writeInstruction(user, 95, instruction);
        var historical = memory.cell(127);
        memory.clearUserSpace();
        memory.clearUserSpace();
        for (int i = 32; i < 128; i++) assertTrue(memory.isEmpty(i));
        assertEquals(new InstructionContent(instruction), historical.content());
        assertSame(pcb, assertInstanceOf(PcbContent.class, memory.read(0)).pcb());
        assertThrows(MemoryAllocationException.class, () -> memory.allocateKernel(1));
        assertThrows(InvalidMemoryReleaseException.class, () -> memory.release(user));
        var reused = memory.allocateUser(96);
        memory.writeInstruction(reused, 0, instruction);
        memory.reset();
        memory.reset();
        for (int i = 0; i < 128; i++) assertTrue(memory.isEmpty(i));
        assertThrows(InvalidMemoryReleaseException.class, () -> memory.release(kernel));
        assertThrows(InvalidMemoryReleaseException.class, () -> memory.release(reused));
        assertEquals(0, memory.allocateKernel(32).base());
        assertEquals(32, memory.allocateUser(96).base());
    }

    @Test
    void kernelReleaseClearsOnlyItsOwnRangeAndRejectsForeignKernelWrites() {
        var memory = memory();
        var first = memory.allocateKernel(1);
        var second = memory.allocateKernel(1);
        var user = memory.allocateUser(1);
        var pcb = new ProcessControlBlock(1, user.base(), user.size());
        memory.writePcb(first, 0, pcb);
        memory.writePcb(second, 0, pcb);
        memory.writeInstruction(user, 0, instruction);
        var foreign = memory().allocateKernel(1);
        assertThrows(MemoryProtectionException.class, () -> memory.writePcb(foreign, 0, pcb));
        assertThrows(InvalidMemoryReleaseException.class, () -> memory.release(foreign));
        memory.release(first);
        assertTrue(memory.isEmpty(0));
        assertSame(pcb, assertInstanceOf(PcbContent.class, memory.read(1)).pcb());
        assertEquals(new InstructionContent(instruction), memory.read(32));
        var reused = memory.allocateKernel(1);
        assertEquals(0, reused.base());
        assertNotEquals(first.allocationId(), reused.allocationId());
        assertThrows(MemoryProtectionException.class, () -> memory.writePcb(first, 0, pcb));
        assertThrows(InvalidMemoryReleaseException.class, () -> memory.release(first));
    }

    @ParameterizedTest
    @ValueSource(ints = {126, 127})
    void clearsSmallUserRegionsWithoutTouchingKernel(int kernelSize) {
        var memory = new MainMemory(new MemoryConfiguration(128, kernelSize));
        var kernel = memory.allocateKernel(kernelSize);
        var user = memory.allocateUser(128 - kernelSize);
        memory.writeUserBlock(user, java.util.Collections.nCopies(user.size(), instruction));
        memory.clearUserSpace();
        assertTrue(memory.isEmpty(127));
        assertEquals(user.base(), memory.allocateUser(user.size()).base());
        memory.release(kernel);
        assertEquals(0, memory.allocateKernel(kernelSize).base());
    }
}
