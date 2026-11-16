package io.github.rajami1205.osimulator.application.cpu;

import io.github.rajami1205.osimulator.application.job.JobSubmissionService;
import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.application.simulator.MultiprocessRuntime;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.instruction.IncInstruction;
import io.github.rajami1205.osimulator.model.job.*;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.scheduling.*;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import java.time.Clock;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CpuTopologyTest {
    @Test void schedulerApiUsesInterfaceAndExplicitPolicyRejectsNull() throws Exception {
        assertEquals(ProcessScheduler.class, CpuNode.class.getMethod("scheduler").getReturnType());
        var f = new Fixture(2);
        assertThrows(NullPointerException.class, () -> new CpuNode(CpuId.ZERO, f.table, f.memory, f.resources, null));
        assertThrows(NullPointerException.class, () -> new CpuTopology(2, f.table, f.memory, f.resources, null));
        var explicit = new CpuTopology(2, f.table, f.memory, f.resources, SchedulingPolicyId.FCFS);
        assertNotSame(explicit.node(CpuId.ZERO).scheduler(), explicit.node(new CpuId(1)).scheduler());
    }
    private static final class Fixture {
        final ProcessTable table;
        final MainMemory memory;
        final SecondaryStorage storage = new SecondaryStorage(2048, 256);
        final ProcessResourceRegistry resources = new ProcessResourceRegistry();
        final JobList jobs = new JobList();
        final JobSubmissionService submission = new JobSubmissionService(storage, jobs);
        final CpuTopology topology;
        final ProcessAdmissionService admission;
        final ProcessSwapService swap;
        final ProcessCompletionService completion;

        Fixture(int count) {
            table = new ProcessTable(count * 5);
            memory = new MainMemory(new MemoryConfiguration(1024, count * 140));
            topology = new CpuTopology(count, table, memory, resources);
            admission = new ProcessAdmissionService(topology, jobs, storage, memory, table, new ProgramLoader(), resources);
            swap = new ProcessSwapService(topology, memory, storage, table, resources);
            completion = new ProcessCompletionService(topology, memory, storage, table, resources, Clock.systemUTC());
        }

        int submit(long arrival) {
            return submission.submit(new ProgramImage("p" + (jobs.entries().size() + 1), List.of(new IncInstruction())), arrival).jobId();
        }
        ProcessControlBlock admit() {
            int pid = assertInstanceOf(AdmissionResult.Admitted.class, admission.admit(submit(0))).processId();
            return table.find(pid).orElseThrow();
        }
        CpuNode node(int id) { return topology.node(new CpuId(id)); }
        List<MemoryContent> image() { return IntStream.range(0, memory.size()).mapToObj(memory::read).toList(); }
        PcbValue kernelCpu(ProcessControlBlock pcb) {
            return ((PcbContent) memory.read(resources.find(pcb.processId()).orElseThrow().kernel().base() + PcbField.CPU_ID.offset())).value();
        }
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5})
    void orderedTopologyHasIndependentLocalResources(int count) {
        var f = new Fixture(count);
        var reversed = new ArrayList<>(f.topology.nodes()); Collections.reverse(reversed);
        var topology = new CpuTopology(reversed); reversed.clear();
        assertEquals(IntStream.range(0, count).boxed().toList(), topology.nodes().stream().map(n -> n.id().value()).toList());
        assertThrows(UnsupportedOperationException.class, () -> topology.nodes().clear());
        f.node(0).registers().restore(f.node(0).registers().snapshot().withDx(new NumericRegisterValue(7)));
        f.node(0).tickCounter().advance();
        var first = f.admit();
        assertEquals(CpuId.ZERO, first.cpuAffinity());
        for (int i = 1; i < count; i++) {
            var other = f.node(i);
            assertNotSame(f.node(0).registers(), other.registers());
            assertNotSame(f.node(0).scheduler(), other.scheduler());
            assertNotSame(f.node(0).dispatcher(), other.dispatcher());
            assertNotSame(f.node(0).suspendedReadyQueue(), other.suspendedReadyQueue());
            assertTrue(other.readyQueue().entries().isEmpty());
            assertEquals(0, other.registers().snapshot().dx()); assertEquals(0, other.tickCounter().current());
        }
    }

    @Test void rejectsInvalidIdsCountsDuplicatesGapsAndForeignSessions() {
        var f = new Fixture(2);
        assertThrows(IllegalArgumentException.class, () -> new CpuId(-1));
        assertThrows(IllegalArgumentException.class, () -> new CpuId(5));
        for (int count : List.of(0, 6)) assertThrows(IllegalArgumentException.class,
                () -> new CpuTopology(count, f.table, f.memory, f.resources));
        assertThrows(IllegalArgumentException.class, () -> new CpuTopology(List.of(f.node(0), f.node(0))));
        assertThrows(IllegalArgumentException.class, () -> new CpuTopology(List.of(f.node(1))));
        assertThrows(IllegalArgumentException.class, () -> f.topology.node(new CpuId(2)));
        var foreign = new CpuNode(new CpuId(1), new ProcessTable(10), f.memory, f.resources);
        assertThrows(IllegalArgumentException.class, () -> new CpuTopology(List.of(f.node(0), foreign)));
        assertThrows(IllegalArgumentException.class, () -> new ProcessSwapService(f.topology, f.memory, f.storage,
                new ProcessTable(10), f.resources));
        assertThrows(IllegalArgumentException.class, () -> new CpuTopology(1, f.table, f.memory, f.resources));
    }

    @Test void leastLoadedCountsSuspendedAndTerminatedUntilRemovalAndRefillsLowestId() {
        var f = new Fixture(2);
        for (int i = 0; i < 10; i++) assertEquals(i % 2, f.admit().cpuAffinity().value());
        assertTrue(f.topology.leastLoaded().isEmpty());
        var pcb = f.table.find(2).orElseThrow(); var times = pcb.logicalTimes();
        f.swap.swapOut(2);
        assertEquals(List.of(2), f.node(1).suspendedReadyQueue().entries());
        assertEquals(5, f.table.countForCpu(new CpuId(1))); assertEquals(times, pcb.logicalTimes());
        int pending = f.submit(0);
        assertInstanceOf(AdmissionResult.Waiting.class, f.admission.admit(pending));
        pcb.changeState(ProcessState.TERMINATED);
        assertTrue(f.topology.leastLoaded().isEmpty());
        var next = pcb.nextPcbAddress(); pcb.setNextPcbAddress(Optional.empty());
        assertThrows(IllegalStateException.class, () -> f.completion.complete(2));
        assertEquals(5, f.table.countForCpu(new CpuId(1))); assertTrue(f.topology.leastLoaded().isEmpty());
        pcb.setNextPcbAddress(next);
        f.completion.complete(2);
        assertEquals(4, f.table.countForCpu(new CpuId(1)));
        assertTrue(f.node(1).suspendedReadyQueue().entries().isEmpty());
        assertEquals(new CpuId(1), f.topology.leastLoaded().orElseThrow().id());
        assertEquals(new AdmissionResult.Admitted(11), f.admission.admit(pending));
        assertEquals(new CpuId(1), f.table.find(11).orElseThrow().cpuAffinity());
        assertEquals(List.of(4, 6, 8, 10, 11), f.node(1).readyQueue().entries());
    }

    @Test void localCapacityCannotBeBypassedThroughTableRegistration() {
        var table = new ProcessTable(25);
        for (int pid = 1; pid <= 5; pid++) table.register(new ProcessControlBlock(pid, 700, 1));
        assertFalse(table.isFull());
        assertThrows(IllegalStateException.class, () -> table.register(new ProcessControlBlock(6, 700, 1)));
        table.find(1).orElseThrow().changeState(ProcessState.TERMINATED);
        assertEquals(5, table.countForCpu(CpuId.ZERO));
        table.remove(1); table.register(new ProcessControlBlock(6, 700, 1));
        assertEquals(5, table.countForCpu(CpuId.ZERO));
    }

    @Test void wrongCpuQueuesSchedulersDispatchAndLegacyRoutesRejectWithoutEffects() {
        var f = new Fixture(2); f.admit(); var pcb = f.admit();
        var before = f.image();
        assertThrows(IllegalStateException.class, () -> f.node(0).readyQueue().enqueue(2));
        assertThrows(IllegalStateException.class, () -> f.node(0).readyQueue().remove(2));
        assertThrows(IllegalStateException.class, () -> f.node(0).suspendedReadyQueue().enqueue(2));
        assertThrows(IllegalStateException.class, () -> f.node(0).dispatcher().dispatch(2));
        var wrong = new ReadyQueue(); wrong.enqueue(2);
        assertThrows(IllegalStateException.class, () -> new FcfsProcessScheduler(CpuId.ZERO, wrong, f.table).selectNext());
        var wrongDispatcher = new Dispatcher(CpuId.ZERO, new CpuRegisters<>(), f.table, wrong, f.resources, f.memory);
        assertThrows(IllegalStateException.class, () -> wrongDispatcher.dispatch(2));
        assertEquals(Optional.of(2), f.node(1).scheduler().selectNext());
        var oldSwap = new ProcessSwapService(f.memory, f.storage, f.table, f.resources, wrong);
        assertThrows(IllegalStateException.class, () -> oldSwap.swapOut(2));
        assertThrows(IllegalArgumentException.class, () -> new ProcessCompletionService(f.memory, f.storage,
                f.table, f.resources, f.node(0).readyQueue(), f.node(1).suspendedReadyQueue(), f.node(0).dispatcher(), Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new FcfsProcessScheduler(CpuId.ZERO, f.node(1).readyQueue(), f.table));
        assertEquals(before, f.image()); assertEquals(ProcessState.READY, pcb.state());
        assertEquals(List.of(2), f.node(1).readyQueue().entries());
        f.node(0).dispatcher().dispatch(1);
        f.node(1).dispatcher().dispatch(2);
        assertSame(pcb, f.node(1).dispatcher().owner().orElseThrow());
        assertEquals(1, f.node(0).dispatcher().owner().orElseThrow().processId());
        assertThrows(IllegalStateException.class, () -> f.node(1).dispatcher().dispatch(2));
    }

    @Test void affinityKernelAndLogicalTimesSurviveSwapAndCompletionWithoutFakeExecution() {
        var f = new Fixture(2); f.admit(); int job = f.submit(3);
        assertThrows(IllegalStateException.class, () -> f.admission.admit(job, 2));
        f.admission.admit(job, 5);
        var pcb = f.table.find(2).orElseThrow(); var context = pcb.cpuContext(); var times = pcb.logicalTimes();
        assertEquals(16, PcbField.CPU_ID.offset()); assertEquals(28, PcbField.PCB_SIZE);
        assertEquals(new PcbValue.Number(1), f.kernelCpu(pcb));
        assertTrue(pcb.accounting().cpuId().isEmpty()); assertEquals(0, pcb.accounting().cpuTicks());
        assertTrue(pcb.accounting().startTime().isEmpty()); assertTrue(times.firstDispatchTime().isEmpty());
        f.swap.swapOut(2); assertEquals(new PcbValue.Number(1), f.kernelCpu(pcb));
        assertFalse(f.node(1).readyQueue().entries().contains(2));
        f.swap.swapIn(2);
        assertEquals(List.of(2), f.node(1).readyQueue().entries()); assertTrue(f.node(1).suspendedReadyQueue().entries().isEmpty());
        assertEquals(times, pcb.logicalTimes()); assertEquals(context, pcb.cpuContext());
        assertEquals(new PcbValue.Number(1), f.kernelCpu(pcb));
        var legacyCompletion = new ProcessCompletionService(f.memory, f.storage, f.table, f.resources,
                f.node(0).readyQueue(), f.node(0).suspendedReadyQueue(), f.node(0).dispatcher(), Clock.systemUTC());
        assertThrows(IllegalStateException.class, () -> legacyCompletion.complete(2));
        f.node(1).readyQueue().remove(2); pcb.changeState(ProcessState.TERMINATED);
        f.completion.complete(2);
        var record = f.completion.completed().getFirst();
        assertEquals(new CpuId(1), record.cpuAffinity()); assertEquals(times, record.logicalTimes());
        assertTrue(record.accounting().cpuId().isEmpty()); assertEquals(0, record.accounting().cpuTicks());
        assertTrue(f.table.find(1).orElseThrow().nextPcbAddress().isEmpty());
        assertEquals(List.of(1), f.node(0).readyQueue().entries());
    }

    @Test void lateAdmissionFailureRestoresLocalQueueCapacityKernelLinksAndPid() throws Exception {
        var f = new Fixture(2); var first = f.admit(); int job = f.submit(0); var before = f.image();
        var replacement = new LinkedHashMap<Integer, Job>() {
            boolean fail = true;
            @Override public Set<Map.Entry<Integer, Job>> entrySet() {
                if (fail) {
                    fail = false;
                    assertEquals(List.of(2), f.node(1).readyQueue().entries());
                    throw new IllegalStateException("Late Job publication failure");
                }
                return super.entrySet();
            }
        };
        f.jobs.entries().forEach(j -> replacement.put(j.jobId(), j));
        var field = JobList.class.getDeclaredField("jobs"); field.setAccessible(true); field.set(f.jobs, replacement);
        assertThrows(IllegalStateException.class, () -> f.admission.admit(job));
        assertEquals(before, f.image()); assertEquals(List.of(first), f.table.entries());
        assertEquals(Set.of(1), f.resources.entries().keySet());
        assertEquals(0, f.table.countForCpu(new CpuId(1))); assertTrue(f.node(1).readyQueue().entries().isEmpty());
        assertTrue(f.node(1).suspendedReadyQueue().entries().isEmpty());
        assertEquals(JobState.PENDING, f.jobs.find(job).orElseThrow().state());
        assertEquals(new AdmissionResult.Admitted(2), f.admission.admit(job));
        assertEquals(new CpuId(1), f.table.find(2).orElseThrow().cpuAffinity());
    }

    @Test void multiCpuRuntimeStillRequiresSharedServices() {
        var f = new Fixture(2);
        assertThrows(NullPointerException.class, () -> new MultiprocessRuntime(f.topology,
                null, f.memory, null, null, f.completion, f.swap, null, f.jobs, f.table, f.resources, null, null));
    }

    @Test void pcbConstructorsUseExplicitZeroCompatibilityAndRejectNullAffinity() {
        assertEquals(CpuId.ZERO, new ProcessControlBlock(1, 140, 1).cpuAffinity());
        var times = LogicalProcessTimes.admitted(2, 3);
        var pcb = new ProcessControlBlock(2, 140, 1, times, new CpuId(4));
        assertEquals(new CpuId(4), pcb.cpuAffinity());
        assertThrows(NullPointerException.class, () -> new ProcessControlBlock(3, 140, 1, times, null));
        assertEquals(new PcbValue.Number(4), PcbMemoryImage.capture(pcb).cells().get(16).value());
        assertTrue(pcb.accounting().cpuId().isEmpty());
    }
}
