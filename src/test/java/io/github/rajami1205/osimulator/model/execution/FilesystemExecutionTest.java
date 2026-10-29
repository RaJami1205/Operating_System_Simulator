package io.github.rajami1205.osimulator.model.execution;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.cpu.exception.RegisterTypeMismatchException;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.filesystem.*;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.instruction.operand.*;
import io.github.rajami1205.osimulator.model.io.*;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.storage.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FilesystemExecutionTest {
    private final SecondaryStorage storage=new SecondaryStorage(128,64);
    private final SimulatedFileSystem filesystem=new SimulatedFileSystem(storage);
    private final MainMemory memory=new MainMemory(new MemoryConfiguration(128,32));
    private final CpuRegisters<Instruction> cpu=new CpuRegisters<>();
    private final ExecutionEngine engine=new ExecutionEngine();
    private final ScreenDevice screen=new ScreenDevice();
    private final KeyboardDevice keyboard=new KeyboardDevice();
    private ProcessControlBlock pcb;
    private io.github.rajami1205.osimulator.model.memory.MemoryAllocation allocation;
    private void load(Instruction instruction) { var loaded = new ProgramLoader().loadWithAllocation(memory,1,List.of(instruction)); pcb = loaded.pcb(); allocation = loaded.userAllocation(); }
    private TickResult tick() { return engine.executeTick(filesystem,screen,keyboard,memory,cpu,pcb,allocation); }
    private void setupService(FileService service) {
        load(new InterruptInstruction(InterruptVector.FILESYSTEM));
        cpu.writeAh(service.code());cpu.writeDx(new TextRegisterValue("file"));
    }
    private List<StorageCell> cells() { return java.util.stream.IntStream.range(0,storage.size()).mapToObj(storage::cell).toList(); }
    @Test void eachServiceCommitsOnFirstTickAndUsesCanonicalStorageAndPcb() {
        setupService(FileService.CREATE);assertEquals(TickResult.PROGRAM_FINISHED,tick());
        assertEquals("",storage.readUserFile("file"));assertTrue(pcb.openFiles().files().isEmpty());
        setupService(FileService.OPEN);tick();assertTrue(pcb.openFiles().contains("file"));
        var opened=pcb;
        // Service tests use distinct legacy instances, so opening state must not leak between them.
        setupService(FileService.WRITE);assertTrue(pcb.openFiles().files().isEmpty());pcb.openFiles().open("file");
        cpu.writeAl(new TextRegisterValue("hello"));tick();assertEquals("hello",storage.readUserFile("file"));
        setupService(FileService.READ);pcb.openFiles().open("file");cpu.writeAl(123);
        var before=cells();tick();assertEquals("hello",cpu.alValue().textValue());assertEquals(before,cells());
        setupService(FileService.DELETE);pcb.openFiles().open("file");tick();
        assertTrue(storage.entries().isEmpty());assertTrue(pcb.openFiles().files().isEmpty());
        assertTrue(opened.openFiles().contains("file")); // F15 does not scan other PCB tables.
    }
    @Test void syscallFailuresPreservePcContentAndOriginalCause() {
        for(FileService service:FileService.values()) {
            setupService(service);
            if(service==FileService.CREATE) storage.createUserFile("file");
            cpu.writeAl(new TextRegisterValue("text"));
            var before=cells();
            var failure=assertThrows(ExecutionEngineException.class,this::tick);
            assertInstanceOf(FileSystemException.class,failure.getCause());assertEquals(before,cells());
            assertEquals(pcb.logicalCodeRange().base().value(),pcb.programCounter());assertEquals(pcb.logicalCodeRange().base().value(),cpu.programCounter());
            assertEquals(InterruptVector.FILESYSTEM,((InterruptInstruction)cpu.instructionRegister().orElseThrow()).vector());
            storage.reset();
        }
        setupService(FileService.CREATE);cpu.writeAh(99);
        assertInstanceOf(FileSystemException.class,assertThrows(ExecutionEngineException.class,this::tick).getCause());
    }
    @Test void numericInstructionsRejectTextBeforeAnySemanticMutation() {
        var dx=RegisterName.DX;var ax=RegisterName.AX;
        var instructions=List.<Instruction>of(new LoadInstruction(dx),new AddInstruction(dx),new SubInstruction(dx),
                new IncInstruction(dx),new DecInstruction(dx),new SwapInstruction(ax,dx),new SwapInstruction(dx,ax),
                new CmpInstruction(ax,dx),new PushInstruction(dx),new MovInstruction(ax,dx),new InterruptInstruction(InterruptVector.SCREEN));
        for(var instruction:instructions) {
            load(instruction);cpu.writeDx(new TextRegisterValue("123"));cpu.writeRegister(ax,7);cpu.writeAccumulator(9);
            var before=cpu.snapshot();
            var error=assertThrows(ExecutionEngineException.class,this::tick);
            assertInstanceOf(RegisterTypeMismatchException.class,error.getCause());
            assertEquals(before.dxValue(),cpu.dxValue());assertEquals(7,cpu.readRegister(ax));assertEquals(9,cpu.accumulator());
            assertEquals(before.conditionFlags(),cpu.conditionFlags());assertTrue(pcb.stack().isEmpty());assertTrue(screen.outputs().isEmpty());
            assertEquals(pcb.logicalCodeRange().base().value(),pcb.programCounter());
        }
    }
    @Test void numericWritesReplaceTextIncludingKeyboardStoreAndPop() {
        for(var instruction:List.<Instruction>of(new MovInstruction(RegisterName.DX,4),new StoreInstruction(RegisterName.DX),
                new PopInstruction(RegisterName.DX),new InterruptInstruction(InterruptVector.KEYBOARD))) {
            load(instruction);cpu.writeDx(new TextRegisterValue("file"));cpu.writeAccumulator(4);pcb.stack().push(4);keyboard.submit(4);
            assertEquals(TickResult.PROGRAM_FINISHED,tick());
            assertEquals(4,cpu.readRegister(RegisterName.DX));
        }
    }
}
