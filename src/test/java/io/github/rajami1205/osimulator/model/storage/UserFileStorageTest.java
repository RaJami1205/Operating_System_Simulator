package io.github.rajami1205.osimulator.model.storage;

import io.github.rajami1205.osimulator.model.instruction.IncInstruction;
import io.github.rajami1205.osimulator.model.storage.exception.StorageException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserFileStorageTest {
    private List<StorageCell> cells(SecondaryStorage storage) {
        return java.util.stream.IntStream.range(0,storage.size()).mapToObj(storage::cell).toList();
    }
    @Test void userFilesShareNamespaceButProgramOperationsAreProtected() {
        var storage=new SecondaryStorage(128,64);
        var empty=storage.createUserFile(" A ");
        assertEquals(FileEntryKind.USER_FILE,empty.kind());assertEquals(0,empty.length());assertEquals(32,empty.startAddress());
        assertSame(EmptyStorageContent.INSTANCE,storage.read(32));
        assertEquals("",storage.readUserFile(" A "));
        assertEquals(33,storage.storeProgram("A",List.of(new IncInstruction())).startAddress());
        assertTrue(storage.findProgram(" A ").isEmpty());assertFalse(storage.removeProgram(" A "));
        assertThrows(StorageException.class,()->storage.readProgram(" A "));
        assertThrows(StorageException.class,()->storage.createUserFile("A"));
        assertThrows(StorageException.class,()->storage.storeProgram(" A ",List.of(new IncInstruction())));
        assertThrows(StorageException.class,()->storage.readUserFile("A"));
        assertThrows(StorageException.class,()->storage.writeUserFile("A","x"));
        assertThrows(StorageException.class,()->storage.deleteUserFile("A"));
        assertEquals(List.of(new IncInstruction()),storage.readProgram("A"));
    }
    @Test void replacementKeepsSlotAndShrinkKeepsPhysicalCapacityUntilDelete() {
        var storage=new SecondaryStorage(128,64);storage.createUserFile("file");
        storage.storeProgram("neighbor",List.of(new IncInstruction()));
        storage.writeUserFile("file","abcd");
        int base=storage.userFile("file").startAddress(); assertEquals(34,base);
        assertEquals(storage.userFile("file"),storage.read(0));
        storage.writeUserFile("file","WXYZ");assertEquals(base,storage.userFile("file").startAddress());
        storage.writeUserFile("file","Q");assertEquals("Q",storage.readUserFile("file"));
        for(int i=1;i<4;i++) assertSame(EmptyStorageContent.INSTANCE,storage.read(base+i));
        storage.writeUserFile("file","");assertEquals(0,storage.userFile("file").length());
        assertEquals(base,storage.userFile("file").startAddress());
        // Growth within retained capacity succeeds in place.
        storage.writeUserFile("file","1234");assertEquals(base,storage.userFile("file").startAddress());
        storage.deleteUserFile("file");assertSame(EmptyStorageContent.INSTANCE,storage.read(0));
        assertEquals(32,storage.createUserFile("reused").startAddress());
        assertEquals(34,storage.storeProgram("block",Collections.nCopies(4,new IncInstruction())).startAddress());
    }
    @Test void utf16UnitsAndFailedGrowthPreserveCanonicalState() {
        var storage=new SecondaryStorage(128,64);storage.createUserFile("file");
        String text="A"+new String(Character.toChars(0x1F600));
        storage.writeUserFile("file",text);
        var entry=storage.userFile("file");assertEquals(3,entry.length());
        for(int i=0;i<text.length();i++) assertEquals(new UserFileContent(text.charAt(i)),storage.read(entry.startAddress()+i));
        assertEquals(text,storage.readUserFile("file"));
        storage.storeProgram("fill",Collections.nCopies(28,new IncInstruction()));
        var before=cells(storage);
        assertThrows(StorageException.class,()->storage.writeUserFile("file","longer"));
        assertEquals(before,cells(storage));assertEquals(text,storage.readUserFile("file"));
        storage.writeUserFile("file","x");assertEquals(entry.startAddress(),storage.userFile("file").startAddress());
        assertThrows(StorageException.class,()->storage.deleteUserFile("missing"));
        storage.reset();assertTrue(storage.entries().isEmpty());assertEquals(32,storage.createUserFile("new").startAddress());
    }
    @Test void indexAndDataExhaustionDoNotLeakOrPublish() {
        var indexFull=new SecondaryStorage(129,64);
        for(int i=0;i<32;i++) indexFull.createUserFile("f"+i);
        var before=cells(indexFull);
        assertThrows(StorageException.class,()->indexFull.createUserFile("overflow"));assertEquals(before,cells(indexFull));
        indexFull.deleteUserFile("f31");assertEquals(63,indexFull.createUserFile("reuse").startAddress());
        var dataFull=new SecondaryStorage(128,64);
        dataFull.storeProgram("full",Collections.nCopies(32,new IncInstruction()));
        before=cells(dataFull);
        assertThrows(StorageException.class,()->dataFull.createUserFile("file"));assertEquals(before,cells(dataFull));
        dataFull.removeProgram("full");assertEquals(32,dataFull.createUserFile("file").startAddress());
    }
    @Test void indexReplacementPreservesSlotAndRejectsMissingOrChangedKind() {
        var index=new FileIndex(2);
        index.publish(new FileIndexEntry("a",32,0,FileEntryKind.USER_FILE));
        index.publish(new FileIndexEntry("b",40,1));
        var replacement=new FileIndexEntry("a",50,3,FileEntryKind.USER_FILE);
        index.replace(replacement);assertSame(replacement,index.read(0));
        assertThrows(StorageException.class,()->index.replace(new FileIndexEntry("a",32,1)));
        assertThrows(StorageException.class,()->index.replace(new FileIndexEntry("missing",32,1)));
        assertSame(replacement,index.read(0));
        assertThrows(IllegalArgumentException.class,()->new FileIndexEntry("p",32,0,FileEntryKind.PROGRAM));
    }
    @Test void retainsRealIdentityOnReuseAndReplacesItOnlyOnSuccessfulGrowth() throws Exception {
        var storage = new SecondaryStorage(128, 64);
        storage.createUserFile("file");
        var field = SecondaryStorage.class.getDeclaredField("allocations");
        field.setAccessible(true);
        Map<?, ?> allocations = assertInstanceOf(Map.class, field.get(storage));
        var original = assertInstanceOf(StorageAllocation.class, allocations.get("file"));
        storage.writeUserFile("file", "a");
        assertSame(original, allocations.get("file"));
        storage.writeUserFile("file", "abcd");
        var grown = assertInstanceOf(StorageAllocation.class, allocations.get("file"));
        assertNotEquals(original, grown);
        storage.writeUserFile("file", "");
        assertSame(grown, allocations.get("file"));
        assertThrows(StorageException.class, () -> storage.writeUserFile("file", "x".repeat(32)));
        assertSame(grown, allocations.get("file"));
        storage.deleteUserFile("file");
        storage.createUserFile("file");
        assertNotEquals(original, allocations.get("file"));
        assertNotEquals(grown, allocations.get("file"));
    }
}
