package io.github.rajami1205.osimulator.support;

import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;

/** Fault injection bypasses protected production writes to exercise corrupted ownership checks. */
public final class PcbImages {
    private PcbImages() {}
    public static void corrupt(MainMemory memory, MemoryAllocation allocation, ProcessControlBlock owner) {
        try {
            var field = MainMemory.class.getDeclaredField("positions");
            field.setAccessible(true);
            var cells = (MemoryContent[]) field.get(memory);
            var image = PcbMemoryImage.capture(owner).cells();
            for (int i = 0; i < image.size(); i++) cells[allocation.base() + i] = image.get(i);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
