package io.github.rajami1205.osimulator.model.storage;

import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.storage.exception.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SwapStorageTest {
    private final SecondaryStorage storage = new SecondaryStorage(128,64);
    private List<Instruction> image(int size) { return Collections.nCopies(size,new IncInstruction()); }

    @Test void physicalTypedImagesAreAtomicImmutableAndIndependentOfIndexData() {
        var program = storage.storeProgram("program", image(2));
        storage.createUserFile("file"); storage.writeUserFile("file","text");
        var entries = storage.entries();
        var allocation = storage.allocateSwap(2);
        assertEquals(64,allocation.base());
        assertThrows(StorageException.class, () -> storage.readSwapBlock(allocation));
        storage.writeSwapBlock(allocation,image(2));
        assertEquals(new StoredInstructionContent(new IncInstruction()), storage.read(64));
        assertEquals(StorageRegion.VIRTUAL_MEMORY,storage.cell(64).region());
        var read = storage.readSwapBlock(allocation);
        assertThrows(UnsupportedOperationException.class,read::clear);
        assertThrows(StorageException.class,()->storage.writeSwapBlock(allocation,image(1)));
        assertThrows(NullPointerException.class,()->storage.writeSwapBlock(allocation,Arrays.asList(new IncInstruction(),null)));
        assertEquals(image(2),storage.readSwapBlock(allocation));
        assertEquals(entries,storage.entries()); assertEquals(program,storage.findProgram("program").orElseThrow());
        assertEquals("text",storage.readUserFile("file"));
        storage.releaseSwap(allocation);
        assertSame(EmptyStorageContent.INSTANCE,storage.read(64));
        assertEquals(entries,storage.entries());
    }

    @Test void firstFitFragmentationCoalescingAndCapacityRemainBounded() {
        var a=storage.allocateSwap(16);var b=storage.allocateSwap(16);
        var c=storage.allocateSwap(16);var d=storage.allocateSwap(16);
        for(var allocation:List.of(a,b,c,d)) storage.writeSwapBlock(allocation,image(16));
        assertThrows(StorageAllocationException.class,()->storage.allocateSwap(1));
        storage.releaseSwap(a);storage.releaseSwap(c);
        assertThrows(StorageAllocationException.class,()->storage.allocateSwap(17));
        var reuse=storage.allocateSwap(16);assertEquals(64,reuse.base());
        storage.releaseSwap(reuse);storage.releaseSwap(b);storage.releaseSwap(d);
        var all=storage.allocateSwap(64);assertEquals(64,all.base());assertEquals(128,all.endExclusive());
        assertTrue(storage.entries().isEmpty());
    }

    @Test void rejectsStaleForeignAlteredAndNonSwapHandlesAndResetInvalidates() {
        var original=storage.allocateSwap(2);
        var foreign=new SecondaryStorage(128,64).allocateSwap(2);
        var data=new FirstFitStorageAllocator(32,64).allocate(2);
        for(var bad:List.of(foreign,data,new StorageAllocation(original.allocationId(),original.base(),1))) {
            assertThrows(StorageException.class,()->storage.readSwapBlock(bad));
            assertThrows(StorageException.class,()->storage.writeSwapBlock(bad,image(2)));
            assertThrows(StorageException.class,()->storage.releaseSwap(bad));
        }
        storage.releaseSwap(original);
        assertThrows(StorageException.class,()->storage.releaseSwap(original));
        var next=storage.allocateSwap(2);assertNotEquals(original,next);
        assertThrows(StorageException.class,()->storage.writeSwapBlock(original,image(2)));
        storage.writeSwapBlock(next,image(2));storage.reset();storage.reset();
        assertSame(EmptyStorageContent.INSTANCE,storage.read(64));
        assertThrows(StorageException.class,()->storage.releaseSwap(next));
        assertEquals(64,storage.allocateSwap(64).size());
    }
}
