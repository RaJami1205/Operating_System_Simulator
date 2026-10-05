package io.github.rajami1205.osimulator.model.filesystem;

import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.storage.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimulatedFileSystemTest {
    private final SecondaryStorage storage=new SecondaryStorage(128,64);
    private final SimulatedFileSystem filesystem=new SimulatedFileSystem(storage);
    private final CpuRegisters<Instruction> cpu=new CpuRegisters<>();
    private final ProcessControlBlock pcb=new ProcessControlBlock(1,32,1);
    private void call(FileService service) { cpu.writeAh(service.code());filesystem.execute(cpu,pcb); }
    @Test void createDoesNotOpenAndReadWriteRequireCurrentPcbOpenState() {
        cpu.writeDx(new TextRegisterValue("file"));call(FileService.CREATE);
        assertFalse(pcb.openFiles().contains("file"));
        assertThrows(FileSystemException.class,()->call(FileService.READ));
        cpu.writeAl(new TextRegisterValue("x"));assertThrows(FileSystemException.class,()->call(FileService.WRITE));
        call(FileService.OPEN);call(FileService.OPEN);assertEquals(1,pcb.openFiles().size());
        call(FileService.READ);assertEquals("",cpu.alValue().textValue());
        cpu.writeAl(new TextRegisterValue("hello"));call(FileService.WRITE);call(FileService.READ);
        assertEquals("hello",cpu.alValue().textValue());
        var other=new ProcessControlBlock(2,33,1);cpu.writeAh(FileService.READ.code());
        assertThrows(FileSystemException.class,()->filesystem.execute(cpu,other));
        call(FileService.DELETE);assertTrue(pcb.openFiles().files().isEmpty());
        call(FileService.CREATE);call(FileService.DELETE); // Delete also accepts a closed file.
    }
    @Test void typeAndKindFailuresAreControlledAndNonDestructive() {
        cpu.writeRegister(RegisterName.DX,1);assertThrows(FileSystemException.class,()->call(FileService.CREATE));
        cpu.writeDx(new TextRegisterValue("file"));call(FileService.CREATE);call(FileService.OPEN);
        cpu.writeAl(123);assertThrows(FileSystemException.class,()->call(FileService.WRITE));assertEquals("",storage.readUserFile("file"));
        storage.storeProgram("program",List.of(new IncInstruction()));cpu.writeDx(new TextRegisterValue("program"));
        for(var service:FileService.values()) assertThrows(FileSystemException.class,()->call(service));
        assertEquals(List.of(new IncInstruction()),storage.readProgram("program"));
        cpu.writeDx(new TextRegisterValue(" "));assertThrows(FileSystemException.class,()->call(FileService.CREATE));
    }
}
