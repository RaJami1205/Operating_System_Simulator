package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.job.JobSubmissionService;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.execution.*;
import io.github.rajami1205.osimulator.model.filesystem.SimulatedFileSystem;
import io.github.rajami1205.osimulator.model.io.ScreenDevice;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.job.*;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.memory.exception.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.scheduling.ReadyQueue;
import io.github.rajami1205.osimulator.model.storage.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DemandPagingTransactionsTest {
    @Test void previousVersionIsRejectedEvenWhenItsFramesRemainActive() {
        var pcb = admit(5); pages.resolve(pcb, pages.request(pcb)); var previous = demand(1).table();
        pcb.setProgramCounter(4100); pages.resolve(pcb, pages.request(pcb)); var current = demand(1).table();
        assertTrue(current.frames().containsAll(previous.frames()));
        assertThrows(MemoryProtectionException.class, () -> memory.resolveInstructionAddress(previous, pcb.logicalCodeRange(), new LogicalAddress(4096)));
        assertInstanceOf(AddressResolution.Present.class, memory.resolveInstructionAddress(current, pcb.logicalCodeRange(), new LogicalAddress(4096)));
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void swapPublicationFailureRestoresRegistryFramesAndBacking(boolean restoring) throws Exception {
        var pcb = admit(5); pages.resolve(pcb, pages.request(pcb));
        if (restoring) swap.swapOut(1);
        var original = registry.find(1).orElseThrow(); var before = memory.frames(); var state = pcb.state();
        var backing = original.residence() instanceof UserImageResidence.Demand d ? d.backing()
                : ((UserImageResidence.DemandSuspended) original.residence()).backing();
        var map = new HashMap<Integer, ProcessResources>(registry.entries()) {
            boolean fail = true;
            @Override public ProcessResources put(Integer key, ProcessResources value) {
                var result = super.put(key, value);
                if (fail) { fail = false; throw new IllegalStateException("Publication failed after replacement"); }
                return result;
            }
        };
        set(registry, "resources", map);
        assertThrows(IllegalStateException.class, () -> { if (restoring) swap.swapIn(1); else swap.swapOut(1); });
        assertSame(original, registry.find(1).orElseThrow()); assertEquals(before, memory.frames()); assertEquals(state, pcb.state());
        assertEquals(5, storage.readSwapBlock(backing.allocation()).size());
        if (restoring) assertEquals(new SwapResult.Completed(), swap.swapIn(1));
        else assertEquals(new SwapResult.Completed(), swap.swapOut(1));
    }
    final MainMemory memory = new MainMemory(new MemoryConfiguration(148, 140), UserAllocationStrategy.FIRST_FIT,
            MemoryMode.PAGING, Optional.empty(), Optional.of(new PagingConfiguration(4, PagingLoadMode.DEMAND)));
    final SecondaryStorage storage = new SecondaryStorage(512, 64);
    final JobList jobs = new JobList();
    final ProcessTable table = new ProcessTable();
    final ProcessResourceRegistry registry = new ProcessResourceRegistry();
    final ReadyQueue ready = new ReadyQueue();
    final ProcessAdmissionService admission = new ProcessAdmissionService(jobs, storage, memory, table, new ProgramLoader(), ready, registry);
    final PageInService pages = new PageInService(memory, storage, registry);
    final ProcessSwapService swap = new ProcessSwapService(memory, storage, table, registry, ready);
    void submit(int id, int size) { storage.storeProgram("p" + id, Collections.nCopies(size, new IncInstruction())); jobs.add(new Job(id, "p" + id, JobState.PENDING)); }
    ProcessControlBlock admit(int size) { submit(1, size); assertEquals(new AdmissionResult.Admitted(1), admission.admit(1)); return table.find(1).orElseThrow(); }
    UserImageResidence.Demand demand(int pid) { return (UserImageResidence.Demand) registry.find(pid).orElseThrow().residence(); }
    static Object field(Object object, String name) throws Exception { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object); }
    static void set(Object object, String name, Object value) throws Exception { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }

    @Test void demandAdmissionHasBackingButNoFramesEvenForImageLargerThanRam() {
        var pcb = admit(13); var d = demand(1);
        assertEquals(4, d.table().entries().size()); assertTrue(d.table().frames().isEmpty());
        assertTrue(memory.frames().stream().allMatch(f -> f.handle().isEmpty()));
        assertEquals(13, storage.readSwapBlock(d.backing().allocation()).size());
        assertEquals(4096, pcb.programCounter()); assertEquals(0, d.table().revision());
        assertThrows(UnsupportedOperationException.class, () -> d.table().entries().clear());
        assertEquals(PcbValue.Empty.INSTANCE, ((PcbContent) memory.read(13)).value());
        assertEquals(new PcbValue.Number(13), ((PcbContent) memory.read(14)).value());
    }
    @Test void permanentBackingLimitRejectedBeforeSubmissionAndTemporaryShortageWaits() {
        var submission = new JobSubmissionService(storage, jobs, memory);
        assertThrows(IncompatibleProgramException.class, () -> submission.submit(new ProgramImage("tooLarge", Collections.nCopies(65, new IncInstruction()))));
        assertTrue(jobs.entries().isEmpty()); assertTrue(storage.entries().isEmpty());
        admit(40); submit(2, 30);
        assertEquals(new AdmissionResult.Waiting(AdmissionResult.Reason.INSUFFICIENT_BACKING_STORE), admission.admit(2));
        assertTrue(table.find(2).isEmpty()); assertTrue(registry.find(2).isEmpty()); assertEquals(JobState.PENDING, jobs.find(2).orElseThrow().state());
        assertEquals(40, storage.readSwapBlock(demand(1).backing().allocation()).size());
    }
    @Test void absentFetchDoesNotMutateCpuPcbOrAccountingAndRetryUsesCurrentVersion() {
        var pcb = admit(5); var old = demand(1).table();
        var cpu = new CpuRegisters<Instruction>(); cpu.setProgramCounter(4096); cpu.writeAccumulator(7);
        var before = cpu.snapshot(); var pcbBefore = pcb.cpuContext(); var accounting = pcb.accounting();
        var engine = new ExecutionEngine(); var fs = new SimulatedFileSystem(storage); var screen = new ScreenDevice();
        assertEquals(TickResult.PAGE_FAULT, engine.executeTick(fs, screen, memory, cpu, pcb, old));
        assertEquals(before, cpu.snapshot()); assertEquals(pcbBefore, pcb.cpuContext()); assertEquals(accounting, pcb.accounting());
        assertTrue(pages.resolve(pcb, pages.request(pcb))); var current = demand(1).table();
        assertEquals(old.imageId(), current.imageId()); assertEquals(1, current.revision());
        assertThrows(MemoryProtectionException.class, () -> memory.validatePageTable(old));
        assertEquals(TickResult.INSTRUCTION_COMPLETED, engine.executeTick(fs, screen, memory, cpu, pcb, current));
        assertEquals(8, cpu.accumulator()); assertEquals(4097, cpu.programCounter());
        assertEquals(0, pcb.accounting().cpuTicks());
    }
    @Test void terminalPaddingForeignTablesAndBackingSegmentsNeverBecomeRecoverableFaults() {
        var pcb = admit(5); var d = demand(1); var t = d.table();
        for (int pc : List.of(4095, 4101, Integer.MAX_VALUE)) assertThrows(RuntimeException.class,
                () -> memory.resolveInstructionAddress(t, pcb.logicalCodeRange(), new LogicalAddress(pc)));
        var foreign = new PageTable(UUID.randomUUID(), 1, 5, 4, t.entries(), 0, PagingLoadMode.DEMAND);
        assertThrows(MemoryProtectionException.class, () -> memory.resolveInstructionAddress(foreign, pcb.logicalCodeRange(), new LogicalAddress(4096)));
        pcb.setProgramCounter(4100); assertTrue(pages.resolve(pcb, pages.request(pcb))); var current = demand(1).table();
        int base = current.entries().get(1).frameHandle().frame().base(); assertTrue(memory.isEmpty(base + 1));
        assertThrows(MemoryProtectionException.class, () -> memory.readInstruction(current, base + 1));
        assertEquals(1, storage.readSwapSegment(d.backing().allocation(), 4, 1).size());
        assertThrows(RuntimeException.class, () -> storage.readSwapSegment(d.backing().allocation(), 4, 2));
        assertThrows(RuntimeException.class, () -> storage.readSwapSegment(d.backing().allocation(), Integer.MAX_VALUE, 1));
    }
    @ParameterizedTest @ValueSource(strings = {"write", "publication", "afterPublication"})
    void pageInFailurePreservesOldTableBackingAndCancelsFrame(String phase) throws Exception {
        var pcb = admit(5); var old = demand(1); var request = pages.request(pcb);
        var original = storage.readSwapBlock(old.backing().allocation());
        if (phase.equals("write")) {
            var pool = field(memory, "frames");
            set(pool, "active", new HashMap<Integer, FrameHandle>() {
                boolean fail = true;
                @Override public FrameHandle get(Object key) {
                    if (fail) { fail = false; throw new IllegalStateException("Injected page write validation failure"); }
                    return super.get(key);
                }
            });
        } else {
            var map = new HashMap<Integer, ProcessResources>(registry.entries()) {
                boolean fail = true;
                @Override public ProcessResources put(Integer key, ProcessResources value) {
                    if (fail) {
                        fail = false;
                        if (phase.equals("afterPublication")) super.put(key, value);
                        throw new IllegalStateException("Injected page publication failure");
                    }
                    return super.put(key, value);
                }
            };
            set(registry, "resources", map);
        }
        assertThrows(RuntimeException.class, () -> pages.resolve(pcb, request));
        assertSame(old, demand(1)); memory.validatePageTable(old.table());
        assertTrue(memory.frames().stream().allMatch(f -> f.handle().isEmpty()));
        assertEquals(original, storage.readSwapBlock(old.backing().allocation()));
        for (int i = 140; i < 148; i++) assertTrue(memory.isEmpty(i));
        assertTrue(pages.resolve(pcb, request));
    }
    @Test void lateAdmissionRollbackReleasesBackingAndAllPublications() throws Exception {
        submit(1, 5);
        var map = new LinkedHashMap<Integer, Job>() {
            boolean fail = true;
            @Override public Set<Map.Entry<Integer, Job>> entrySet() {
                if (fail) { fail = false; throw new IllegalStateException("Job publication failure"); }
                return super.entrySet();
            }
        };
        jobs.entries().forEach(j -> map.put(j.jobId(), j)); set(jobs, "jobs", map);
        assertThrows(IllegalStateException.class, () -> admission.admit(1));
        assertTrue(table.entries().isEmpty()); assertTrue(registry.entries().isEmpty()); assertTrue(ready.entries().isEmpty());
        var all = storage.allocateSwap(64); storage.releaseSwap(all);
        assertEquals(new AdmissionResult.Admitted(1), admission.admit(1));
        assertEquals(0, registry.find(1).orElseThrow().kernel().base());
    }
    @Test void partialSwapUsesSameBackingInvalidatesVersionsAndRestoresZeroPages() {
        var pcb = admit(7); pages.resolve(pcb, pages.request(pcb)); var old = demand(1); var context = pcb.cpuContext();
        var oldFrame = old.table().frames().getFirst();
        swap.swapOut(1); var suspended = (UserImageResidence.DemandSuspended) registry.find(1).orElseThrow().residence();
        assertEquals(old.backing(), suspended.backing()); assertTrue(suspended.table().frames().isEmpty());
        assertThrows(MemoryProtectionException.class, () -> memory.validatePageTable(old.table()));
        swap.swapIn(1); var current = demand(1); assertEquals(old.backing(), current.backing());
        assertTrue(current.table().frames().isEmpty()); assertEquals(context, pcb.cpuContext());
        pages.resolve(pcb, pages.request(pcb)); var reused = demand(1).table().frames().getFirst();
        assertEquals(oldFrame.frame(), reused.frame()); assertNotEquals(oldFrame, reused);
        assertThrows(MemoryProtectionException.class, () -> memory.validatePageTable(suspended.table()));
        var remainder = storage.allocateSwap(57); storage.releaseSwap(remainder);
    }
    @Test void blockedDemandSuspensionIsExplicitlyUnsupported() {
        var pcb = admit(5); ready.remove(1); pcb.changeState(ProcessState.BLOCKED);
        var before = demand(1);
        assertThrows(IllegalStateException.class, () -> swap.swapOut(1));
        assertSame(before, demand(1)); assertEquals(ProcessState.BLOCKED, pcb.state());
    }
}
