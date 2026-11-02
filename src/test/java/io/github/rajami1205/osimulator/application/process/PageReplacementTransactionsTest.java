package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.execution.*;
import io.github.rajami1205.osimulator.model.filesystem.SimulatedFileSystem;
import io.github.rajami1205.osimulator.model.io.ScreenDevice;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.rajami1205.osimulator.application.process.DemandPagingTransactionsTest.*;

class PageReplacementTransactionsTest {
    final DemandPagingTransactionsTest f = new DemandPagingTransactionsTest();
    void load(io.github.rajami1205.osimulator.model.process.ProcessControlBlock pcb, int page) {
        pcb.setProgramCounter(4096 + page * 4); assertTrue(f.pages.resolve(pcb, f.pages.request(pcb)));
    }
    @Test void samePidReplacementUsesOneRevisionNewUuidAndCanReloadVictim() {
        var pcb = f.admit(9); load(pcb, 0); load(pcb, 1);
        var old = f.demand(1); var handle = old.table().entries().getFirst().frameHandle();
        assertEquals(0, f.memory.clockView().orElseThrow().hand()); assertTrue(f.registry.pageEvents().isEmpty());
        load(pcb, 2); var current = f.demand(1); var replacement = current.table().entries().get(2).frameHandle();
        assertEquals(old.table().revision() + 1, current.table().revision()); assertEquals(old.backing(), current.backing());
        assertEquals(handle.frame(), replacement.frame()); assertNotEquals(handle.allocationId(), replacement.allocationId());
        assertTrue(current.table().entries().getFirst().mapping().isEmpty());
        assertThrows(MemoryProtectionException.class, () -> f.memory.validatePageTable(old.table()));
        assertEquals(1, f.memory.clockView().orElseThrow().hand());
        assertFalse(f.memory.clockView().orElseThrow().references().containsKey(handle));
        for (int i = 1; i < 4; i++) assertTrue(f.memory.isEmpty(replacement.frame().base() + i));
        assertThrows(MemoryProtectionException.class, () -> f.memory.readInstruction(current.table(), replacement.frame().base() + 1));
        load(pcb, 0); assertEquals(new IncInstruction(), f.memory.readInstruction(f.demand(1).table(), f.demand(1).table().entries().getFirst().frameHandle().frame().base()));
        assertEquals(0, f.memory.clockView().orElseThrow().hand());
        assertEquals(2, f.registry.pageEvents().size());
        assertEquals(new PageFaultEvent.Eviction(1, 0, 0), f.registry.pageEvents().getFirst().eviction().orElseThrow());
    }
    @Test void crossPidPublicationRevisesBothTablesAndPreservesBothBackings() {
        var one = f.admit(5); load(one, 0); load(one, 1);
        f.submit(2, 1); f.admission.admit(2); var two = f.table.find(2).orElseThrow();
        var beforeOne = f.demand(1); var beforeTwo = f.demand(2); load(two, 0);
        assertEquals(beforeOne.table().revision() + 1, f.demand(1).table().revision());
        assertEquals(beforeTwo.table().revision() + 1, f.demand(2).table().revision());
        assertEquals(beforeOne.backing(), f.demand(1).backing()); assertEquals(beforeTwo.backing(), f.demand(2).backing());
        f.memory.validatePageTable(f.demand(1).table()); f.memory.validatePageTable(f.demand(2).table());
        assertEquals(5, f.storage.readSwapBlock(beforeOne.backing().allocation()).size());
        assertEquals(1, f.storage.readSwapBlock(beforeTwo.backing().allocation()).size());
    }
    @ParameterizedTest @ValueSource(strings = {"registryBefore", "registryAfterFirst", "registryAfterSecond", "memoryFirst", "memorySecond"})
    void publicationFailureRestoresBothOwnersPhysicalCellsIdentityAndClock(String phase) throws Exception {
        var one = f.admit(5); load(one, 0); load(one, 1); f.submit(2, 1); f.admission.admit(2);
        var two = f.table.find(2).orElseThrow(); var before = f.registry.entries();
        var frames = f.memory.frames(); var clock = f.memory.clockView();
        var contents = java.util.stream.IntStream.range(140, 148).mapToObj(f.memory::read).toList();
        int failAt = phase.endsWith("Second") ? 2 : 1;
        if (phase.startsWith("registry")) {
            set(f.registry, "resources", new HashMap<Integer, ProcessResources>(before) {
                int calls;
                @Override public ProcessResources put(Integer key, ProcessResources value) {
                    boolean fail = ++calls == failAt;
                    if (fail && phase.equals("registryBefore")) throw new IllegalStateException("injected registry write");
                    var old = super.put(key, value);
                    if (fail) throw new IllegalStateException("injected registry write");
                    return old;
                }
            });
        } else {
            @SuppressWarnings("unchecked") var old = (Map<Integer, PageTable>) field(f.memory, "pageTables");
            set(f.memory, "pageTables", new HashMap<Integer, PageTable>(old) {
                int calls;
                @Override public PageTable put(Integer key, PageTable value) {
                    var result = super.put(key, value);
                    if (++calls == failAt) throw new IllegalStateException("injected table write");
                    return result;
                }
            });
        }
        var error = assertThrows(IllegalStateException.class, () -> f.pages.resolve(two, f.pages.request(two)));
        assertTrue(error.getMessage().startsWith("injected")); assertEquals(before, f.registry.entries());
        before.forEach((pid, resource) -> assertSame(resource, f.registry.find(pid).orElseThrow()));
        assertEquals(frames, f.memory.frames()); assertEquals(clock, f.memory.clockView());
        assertEquals(contents, java.util.stream.IntStream.range(140, 148).mapToObj(f.memory::read).toList());
        assertTrue(f.registry.pageEvents().isEmpty());
        f.memory.validatePageTable(f.demand(1).table()); f.memory.validatePageTable(f.demand(2).table());
        assertTrue(f.pages.resolve(two, f.pages.request(two)));
    }
    @Test void failedWriteCanCancelWithoutInvalidatingVictimOrPublishingTables() {
        var pcb = f.admit(9); load(pcb, 0); load(pcb, 1); var before = f.demand(1).table();
        var clock = f.memory.clockView(); var frames = f.memory.frames();
        var selection = f.memory.selectVictim(before.frames()).orElseThrow();
        var token = f.memory.reserveReplacement(before, 2, before, 0, selection);
        assertEquals(frames, f.memory.frames()); f.memory.validatePageTable(before);
        assertThrows(MemoryProtectionException.class, () -> f.memory.writePage(token, List.of(new IncInstruction(), new IncInstruction())));
        f.memory.cancelPage(token);
        assertEquals(frames, f.memory.frames()); assertEquals(clock, f.memory.clockView()); f.memory.validatePageTable(before);
        assertTrue(f.registry.pageEvents().isEmpty());
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void invalidOrDivergentVictimBackingAbortsWithoutEviction(boolean missing) {
        var one = f.admit(5); load(one, 0); load(one, 1); f.submit(2, 1); f.admission.admit(2);
        var two = f.table.find(2).orElseThrow(); var before = f.registry.entries(); var frames = f.memory.frames(); var clock = f.memory.clockView();
        var backing = f.demand(1).backing();
        if (missing) f.storage.releaseSwap(backing.allocation());
        else f.storage.writeSwapBlock(backing.allocation(), Collections.nCopies(5, new DecInstruction()));
        assertThrows(RuntimeException.class, () -> f.pages.resolve(two, f.pages.request(two)));
        assertEquals(before, f.registry.entries()); assertEquals(frames, f.memory.frames()); assertEquals(clock, f.memory.clockView());
        assertTrue(f.registry.pageEvents().isEmpty());
    }
    @Test void freeFirstFetchOnlyMarksItsFrameAndAdministrativeReadsArePure() {
        var pcb = f.admit(9); load(pcb, 0); load(pcb, 1); load(pcb, 2);
        var table = f.demand(1).table(); var handle = table.entries().get(1).frameHandle(); var before = f.memory.clockView();
        assertFalse(before.orElseThrow().references().get(handle));
        var range = pcb.logicalCodeRange(); f.memory.validatePageTable(table);
        f.memory.resolveInstructionAddress(table, range, new LogicalAddress(4100)); f.memory.readInstruction(table, handle.frame().base());
        assertTrue(f.pages.canResolve()); assertEquals(before, f.memory.clockView());
        var cpu = new CpuRegisters<Instruction>(); cpu.setProgramCounter(4100); pcb.setProgramCounter(4100);
        new ExecutionEngine().executeTick(new SimulatedFileSystem(f.storage), new ScreenDevice(), f.memory, cpu, pcb, table);
        assertTrue(f.memory.clockView().orElseThrow().references().get(handle));
        assertEquals(before.orElseThrow().hand(), f.memory.clockView().orElseThrow().hand());
    }
    @Test void provisionalFramesAreNotVictimsAndSwapReleasePreservesHand() {
        var pcb = f.admit(9); load(pcb, 0);
        var token = f.memory.reservePage(f.demand(1).table(), 1); var before = f.memory.clockView();
        assertFalse(f.pages.canResolve()); assertTrue(f.memory.selectVictim(List.of()).isEmpty());
        assertEquals(before, f.memory.clockView()); f.memory.cancelPage(token);
        load(pcb, 1); load(pcb, 2); int hand = f.memory.clockView().orElseThrow().hand();
        f.swap.swapOut(1); assertTrue(f.memory.clockView().orElseThrow().references().isEmpty());
        assertEquals(hand, f.memory.clockView().orElseThrow().hand());
        f.memory.reset(); assertEquals(new ClockReplacement.View(0, Map.of()), f.memory.clockView().orElseThrow());
    }
    @Test void protectedCompletionIsARealNoVictimCaseAndAvailabilityDoesNotRotateHand() {
        var pcb = f.admit(5); load(pcb, 0); load(pcb, 1); var before = f.memory.clockView();
        pcb.changeState(io.github.rajami1205.osimulator.model.process.ProcessState.RUNNING);
        pcb.changeState(io.github.rajami1205.osimulator.model.process.ProcessState.TERMINATED);
        f.memory.protectImageForCompletion(1);
        f.submit(2, 1); f.admission.admit(2); var requester = f.table.find(2).orElseThrow();
        assertFalse(f.pages.canResolve()); assertFalse(f.pages.resolve(requester, f.pages.request(requester)));
        assertEquals(before, f.memory.clockView()); assertTrue(f.registry.pageEvents().isEmpty());
    }
    @Test void fullyResidentHasNoClockOrEligibleVictims() {
        var memory = new MainMemory(new MemoryConfiguration(148, 140), UserAllocationStrategy.FIRST_FIT,
                MemoryMode.PAGING, Optional.empty(), Optional.of(new PagingConfiguration(4)));
        var token = memory.reserveImage(1, 8); memory.writeImage(token.image(), Collections.nCopies(8, new IncInstruction())); memory.confirmUser(token);
        assertTrue(memory.clockView().isEmpty()); assertTrue(memory.demandReplacementImages().isEmpty());
        assertTrue(memory.selectVictim(((PageTable) token.image()).frames()).isEmpty());
    }
    @Test void samePidFailedPublicationRestoresFullVictimIncludingPadding() throws Exception {
        var pcb = f.admit(9); load(pcb, 2); load(pcb, 1); var old = f.demand(1); var before = f.memory.clockView();
        var cells = java.util.stream.IntStream.range(140, 148).mapToObj(f.memory::read).toList();
        var frames = f.memory.frames();
        set(f.registry, "resources", new HashMap<Integer, ProcessResources>(f.registry.entries()) {
            boolean fail = true;
            @Override public ProcessResources put(Integer key, ProcessResources value) {
                var result = super.put(key, value);
                if (fail) { fail = false; throw new IllegalStateException("same PID publication"); }
                return result;
            }
        });
        pcb.setProgramCounter(4096);
        assertThrows(IllegalStateException.class, () -> f.pages.resolve(pcb, f.pages.request(pcb)));
        assertSame(old, f.demand(1)); assertEquals(before, f.memory.clockView()); assertEquals(frames, f.memory.frames());
        assertEquals(cells, java.util.stream.IntStream.range(140, 148).mapToObj(f.memory::read).toList());
        assertTrue(f.registry.pageEvents().isEmpty()); load(pcb, 0);
    }
    @Test void staleRegistryAndRevisionOverflowAbortBeforeSelectionCommit() throws Exception {
        var pcb = f.admit(9); var stale = f.registry.find(1).orElseThrow(); load(pcb, 0); load(pcb, 1);
        var current = f.registry.find(1).orElseThrow(); var clock = f.memory.clockView(); var frames = f.memory.frames();
        f.registry.replace(1, current, stale);
        assertThrows(MemoryProtectionException.class, f.pages::canResolve);
        f.registry.replace(1, stale, current);
        var d = f.demand(1); var table = d.table();
        var overflow = new PageTable(table.imageId(), table.processId(), table.programLength(), table.pageSize(), table.entries(), Long.MAX_VALUE, table.loadMode());
        @SuppressWarnings("unchecked") var mappings = (Map<Integer, PageTable>) field(f.memory, "pageTables");
        mappings.put(1, overflow);
        f.registry.replace(1, current, current.withResidence(new UserImageResidence.Demand(overflow, d.backing())));
        pcb.setProgramCounter(4104);
        assertThrows(ArithmeticException.class, () -> f.pages.resolve(pcb, f.pages.request(pcb)));
        assertEquals(clock, f.memory.clockView()); assertEquals(frames, f.memory.frames());
        assertTrue(f.registry.pageEvents().isEmpty()); f.memory.validatePageTable(overflow);
    }
    @Test void successfulFetchSetsPreviouslyClearedBitEvenWhenInstructionFails() {
        var program = new ArrayList<Instruction>(Collections.nCopies(9, new IncInstruction()));
        program.set(4, new PopInstruction(RegisterName.AX));
        f.storage.storeProgram("failure", program);
        f.jobs.add(new io.github.rajami1205.osimulator.model.job.Job(1, "failure", io.github.rajami1205.osimulator.model.job.JobState.PENDING));
        f.admission.admit(1); var pcb = f.table.find(1).orElseThrow();
        load(pcb, 0); load(pcb, 1); load(pcb, 2); pcb.setProgramCounter(4100);
        var table = f.demand(1).table(); var handle = table.entries().get(1).frameHandle();
        assertFalse(f.memory.clockView().orElseThrow().references().get(handle));
        var cpu = new CpuRegisters<Instruction>(); cpu.setProgramCounter(4100);
        assertThrows(RuntimeException.class, () -> new ExecutionEngine().executeTick(new SimulatedFileSystem(f.storage), new ScreenDevice(), f.memory, cpu, pcb, table));
        assertTrue(f.memory.clockView().orElseThrow().references().get(handle));
        assertEquals(0, pcb.accounting().cpuTicks());
    }
}
