package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.cpu.CpuId;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.memory.exception.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PagingMemoryTest {
    @Test void invalidLastFramePreventsAnyReleaseOrClearing() throws Exception {
        var m = memory(); var image = load(m, 1, 9); var content = m.readImage(image);
        var field = MainMemory.class.getDeclaredField("frames"); field.setAccessible(true);
        var activeField = FrameAllocator.class.getDeclaredField("active"); activeField.setAccessible(true);
        @SuppressWarnings("unchecked") var active = (Map<Integer, FrameHandle>) activeField.get(field.get(m));
        var last = image.frames().getLast(); active.remove(last.frame().id());
        var before = m.frames();
        assertThrows(MemoryProtectionException.class, () -> m.release(image));
        assertEquals(before, m.frames()); assertFalse(m.isEmpty(image.frames().getFirst().frame().base()));
        active.put(last.frame().id(), last); assertEquals(content, m.readImage(image)); m.release(image);
        assertTrue(m.frames().stream().allMatch(f -> f.handle().isEmpty()));
        for (int address = 140; address < m.size(); address++) assertTrue(m.isEmpty(address));
    }
    private MainMemory memory() {
        return new MainMemory(new MemoryConfiguration(161, 140), UserAllocationStrategy.FIRST_FIT,
                MemoryMode.PAGING, Optional.empty());
    }
    private PageTable load(MainMemory m, int pid, int length) {
        return (PageTable) new ProgramLoader().loadImage(m, pid, Collections.nCopies(length, new IncInstruction()),
                LogicalProcessTimes.admitted(0, 0), CpuId.ZERO).image();
    }
    @Test void frameSetsAreDeterministicAtomicAndRejectStaleForeignModifiedDuplicates() {
        var geometry = new MemoryConfiguration(161, 140);
        var pool = new FrameAllocator(geometry, new PagingConfiguration(4));
        var first = pool.allocate(1); var middle = pool.allocate(1); var third = pool.allocate(1);
        pool.releaseAll(middle);
        var scattered = pool.allocate(2);
        assertEquals(List.of(1, 3), scattered.stream().map(h -> h.frame().id()).toList());
        var before = pool.snapshot();
        assertThrows(MemoryAllocationException.class, () -> pool.allocate(2));
        var foreign = new FrameAllocator(geometry, new PagingConfiguration(4)).allocate(1).getFirst();
        var h = first.getFirst();
        var modified = new FrameHandle(h.allocationId(), new Frame(h.frame().id(), 0, 4));
        for (var invalid : List.of(middle.getFirst(), foreign, modified)) {
            assertThrows(InvalidMemoryReleaseException.class, () -> pool.releaseAll(List.of(third.getFirst(), invalid)));
            assertEquals(before, pool.snapshot());
        }
        assertThrows(InvalidMemoryReleaseException.class, () -> pool.releaseAll(List.of(h, h)));
        pool.releaseAll(first);
        assertThrows(InvalidMemoryReleaseException.class, () -> pool.releaseAll(first));
        var reused = pool.allocate(1).getFirst(); assertEquals(h.frame(), reused.frame()); assertNotEquals(h, reused);
        pool.reset(); assertFalse(pool.isActive(reused)); assertNotEquals(reused, pool.allocate(1).getFirst());
    }
    @Test void scatteredMappingProtectsPaddingTerminalKernelAndOwnerAndReset() {
        var m = memory(); var a = load(m, 1, 4); var b = load(m, 2, 4); var c = load(m, 3, 4);
        m.release(a); m.release(c);
        var image = load(m, 4, 7);
        assertEquals(List.of(0, 2), image.frames().stream().map(h -> h.frame().id()).toList());
        var pcb = ProcessControlBlock.paged(4, 7, LogicalProcessTimes.admitted(0, 0), CpuId.ZERO);
        m.validateImage(image, pcb);
        assertEquals(4096, pcb.programCounter()); assertEquals(7, pcb.instructionCount());
        assertThrows(IllegalStateException.class, pcb::memoryBounds);
        assertEquals(148, m.translateInstructionAddress(image, pcb.logicalCodeRange(), new LogicalAddress(4100)).value());
        assertEquals(7, m.readImage(image).size()); assertTrue(m.isEmpty(151));
        assertThrows(RuntimeException.class, () -> m.readInstruction(image, 151));
        assertThrows(RuntimeException.class, () -> m.readInstruction(image, 0));
        for (int address : List.of(4095, 4103, Integer.MAX_VALUE)) {
            assertThrows(RuntimeException.class, () -> m.translateInstructionAddress(image, pcb.logicalCodeRange(), new LogicalAddress(address)));
        }
        assertThrows(UnsupportedOperationException.class, () -> image.entries().clear());
        var forged = new PageTable(image.imageId(), 8, 7, 4, image.entries());
        assertThrows(MemoryProtectionException.class, () -> m.readImage(forged));
        assertThrows(MemoryProtectionException.class, () -> m.validateImage(image,
                ProcessControlBlock.paged(5, 7, LogicalProcessTimes.admitted(0, 0), CpuId.ZERO)));
        var cells = PcbMemoryImage.capture(pcb).cells();
        assertEquals(28, cells.size()); assertEquals(13, PcbField.BASE.offset()); assertEquals(14, PcbField.LIMIT.offset());
        assertEquals(PcbValue.Empty.INSTANCE, cells.get(13).value()); assertEquals(new PcbValue.Number(7), cells.get(14).value());
        assertEquals(1, m.unassignableUserCells()); assertTrue(m.isEmpty(160));
        var original = m.readImage(image);
        assertThrows(RuntimeException.class, () -> m.writeImage(image, List.of(new IncInstruction())));
        assertEquals(original, m.readImage(image));
        m.clearUserSpace(); assertThrows(MemoryProtectionException.class, () -> m.readImage(image));
        var fresh = load(m, 4, 7); assertNotEquals(image.imageId(), fresh.imageId());
        assertThrows(MemoryProtectionException.class, () -> m.release(image));
        assertEquals(7, m.readImage(fresh).size());
        assertThrows(MemoryProtectionException.class, () -> m.readImage(b));
    }
    @Test void reservationCancelInvalidatesImageAndTokenAndPreservesOtherImages() {
        var m = memory(); var existing = load(m, 1, 4); var before = m.frames();
        var token = m.reserveImage(2, 5); var image = token.image();
        assertThrows(IllegalStateException.class, () -> m.reserveImage(3, 1));
        m.cancelUser(token); assertEquals(before, m.frames());
        assertThrows(IllegalStateException.class, () -> m.confirmUser(token));
        assertThrows(MemoryProtectionException.class, () -> m.readImage(image));
        assertEquals(4, m.readImage(existing).size());
        assertThrows(IllegalArgumentException.class, () -> m.reserveImage(1, 1));
        assertThrows(IncompatibleProgramException.class, () -> m.reserveImage(2, 21));
        assertEquals(before, m.frames());
    }
    @Test void loaderWriteFailureCancelsAllFramesAndAllowsRetry() throws Exception {
        var m = memory(); var field = MainMemory.class.getDeclaredField("frames"); field.setAccessible(true);
        var pool = field.get(m); var active = FrameAllocator.class.getDeclaredField("active"); active.setAccessible(true);
        active.set(pool, new HashMap<Integer, FrameHandle>() {
            boolean fail = true;
            @Override public FrameHandle get(Object key) {
                if (fail) { fail = false; throw new IllegalStateException("Injected write validation failure"); }
                return super.get(key);
            }
        });
        assertThrows(RuntimeException.class, () -> load(m, 1, 7));
        assertTrue(m.frames().stream().allMatch(f -> f.handle().isEmpty()));
        assertEquals(7, load(m, 1, 7).size());
    }
    @Test void geometryChecksDoNotRequirePowersOfTwoAndCountsAvoidOverflow() {
        assertThrows(IllegalArgumentException.class, () -> new PagingConfiguration(0));
        assertThrows(IllegalArgumentException.class, () -> new PagingConfiguration(-1));
        assertThrows(IllegalArgumentException.class, () -> new PagingConfiguration(22).validate(new MemoryConfiguration(161, 140)));
        var pool = new FrameAllocator(new MemoryConfiguration(161, 140), new PagingConfiguration(3));
        assertEquals(7, pool.count()); assertEquals(158, pool.snapshot().getLast().frame().base());
        assertEquals(Integer.MAX_VALUE, new PagingConfiguration(1).requiredFrames(Integer.MAX_VALUE));
        assertThrows(RuntimeException.class, () -> new PagingConfiguration(3).requiredFrames(0));
        var m = memory(); assertThrows(RuntimeException.class, () -> m.reserveImage(1, Integer.MAX_VALUE));
        assertTrue(m.frames().stream().allMatch(f -> f.handle().isEmpty()));
    }
}
