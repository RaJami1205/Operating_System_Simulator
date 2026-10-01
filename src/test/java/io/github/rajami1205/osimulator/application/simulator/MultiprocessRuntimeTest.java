package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.infrastructure.asm.AsmParser;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.execution.*;
import io.github.rajami1205.osimulator.model.execution.exception.ExecutionEngineException;
import io.github.rajami1205.osimulator.model.instruction.*;
import io.github.rajami1205.osimulator.model.job.JobState;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.scheduling.*;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MultiprocessRuntimeTest {
    private final SimulatorOrchestrator simulator=new SimulatorOrchestrator(new ProgramLoader(),new ExecutionEngine());
    private final AsmParser parser=new AsmParser();
    private void initialize() { simulator.initialize(new SimulatorConfiguration(new MemoryConfiguration(128,32),512,64)); }
    private void submit(String name,String... instructions) { simulator.submitProgram(new ProgramImage(name,parser.parse(List.of(instructions)))); }
    private <T> T field(Object object,String name,Class<T> type) {
        try { var f=object.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(object)); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private ProcessTable table() { return field(simulator,"processTable",ProcessTable.class); }
    private ProcessResourceRegistry resources() { return field(simulator,"processResources",ProcessResourceRegistry.class); }
    private ReadyQueue ready() { return field(simulator,"readyQueue",ReadyQueue.class); }
    private ExecutionProgress progress() { return field(simulator,"executionProgress",ExecutionProgress.class); }
    private Dispatcher dispatcher() { return field(simulator,"dispatcher",Dispatcher.class); }
    private MainMemory memory() { return field(simulator,"memory",MainMemory.class); }
    private SecondaryStorage storage() { return field(simulator,"secondaryStorage",SecondaryStorage.class); }
    private SuspendedReadyQueue suspended() { return field(field(simulator,"runtime",MultiprocessRuntime.class),"suspended",SuspendedReadyQueue.class); }
    private ProcessControlBlock pcb(int pid) { return table().find(pid).orElseThrow(); }
    private void tick(int pid,TickResult tick,RuntimeStatus status) {
        assertEquals(new RuntimeStepResult.Executed(pid,tick,status),simulator.step());
    }
    private void block(int pid,RuntimeStatus status) {
        tick(pid,TickResult.IN_PROGRESS,RuntimeStatus.RUNNABLE);tick(pid,TickResult.WAITING_FOR_INPUT,status);
        assertTrue(progress().isIdle());assertTrue(dispatcher().owner().isEmpty());
        assertTrue(simulator.snapshot().process().isEmpty());assertTrue(simulator.snapshot().program().isEmpty());
    }
    @Test void fcfsOneTickNoPreemptionOrContextLeakAndFinalResourcesAreReleased() {
        initialize();submit("first","MOV DX, \"first\"","MOV AL, \"data\"","MOV AH, 40H","CMP AX, BX","ADD AX");
        submit("second","ADD AX");simulator.start();
        assertTrue(dispatcher().owner().isEmpty());assertTrue(progress().isIdle());
        assertEquals(0,simulator.snapshot().cpu().orElseThrow().programCounter());
        assertEquals(List.of(1,2),ready().entries());
        for(int i=0;i<3;i++) tick(1,TickResult.INSTRUCTION_COMPLETED,RuntimeStatus.RUNNABLE);
        // CMP costs two ticks; ADD costs three. Neither may switch owner to P2.
        var cmp=parser.parse(List.of("CMP AX, BX")).getFirst();
        for(int i=1;i<=cmp.executionWeight().ticks();i++) tick(1,i==cmp.executionWeight().ticks()?TickResult.INSTRUCTION_COMPLETED:TickResult.IN_PROGRESS,RuntimeStatus.RUNNABLE);
        tick(1,TickResult.IN_PROGRESS,RuntimeStatus.RUNNABLE);
        var owner=dispatcher().owner().orElseThrow();var before=simulator.snapshot();
        simulator.pause();assertSame(owner,dispatcher().owner().orElseThrow());assertEquals(1,progress().consumedTicks());
        assertThrows(IllegalStateException.class,simulator::step);simulator.resume();assertEquals(before,simulator.snapshot());
        tick(1,TickResult.IN_PROGRESS,RuntimeStatus.RUNNABLE);
        tick(1,TickResult.PROGRAM_FINISHED,RuntimeStatus.RUNNABLE);
        assertEquals(0,pcb(2).programCounter());assertTrue(dispatcher().owner().isEmpty());
        var first=simulator.completedProcesses().getFirst().finalContext();
        assertEquals("first",first.dxValue().textValue());assertEquals("data",first.alValue().textValue());
        assertEquals(0x40,first.ah());assertTrue(first.conditionFlags().equal());
        tick(2,TickResult.IN_PROGRESS,RuntimeStatus.RUNNABLE);
        assertEquals(0,simulator.snapshot().cpu().orElseThrow().dx());
        // Full restore clears AL/AH/flags and installs P2 IR on fetch.
        CpuRegisters<?> activeCpu=field(simulator,"cpu",CpuRegisters.class);
        var active=activeCpu.snapshot();
        assertEquals(0,active.al());assertEquals(0,active.ah());assertEquals(ConditionFlags.CLEAR,active.conditionFlags());
        tick(2,TickResult.IN_PROGRESS,RuntimeStatus.RUNNABLE);tick(2,TickResult.PROGRAM_FINISHED,RuntimeStatus.FINISHED);
        assertEquals(SimulatorState.FINISHED,simulator.snapshot().simulatorState());
        assertEquals(0,table().size());assertTrue(resources().entries().isEmpty());assertTrue(ready().entries().isEmpty());
        assertTrue(simulator.snapshot().memory().stream().allMatch(row->row.content().isEmpty()));
        assertEquals(2,storage().entries().size());assertEquals(32,memory().allocateUser(96).base());
        assertEquals(0,memory().allocateKernel(32).base());
    }
    @Test void inputCompletionDoesNotMutateAnotherOwnerAndJoinsReadyTail() {
        initialize();submit("input","INT 09H","INC");submit("cpu","ADD AX");submit("third","INC");simulator.start();
        block(1,RuntimeStatus.RUNNABLE);tick(2,TickResult.IN_PROGRESS,RuntimeStatus.RUNNABLE);
        var before=simulator.snapshot();simulator.submitKeyboardInput(42);
        assertEquals(before,simulator.snapshot());assertEquals(1,progress().consumedTicks());
        assertEquals(List.of(3,1),ready().entries());assertEquals(42,pcb(1).cpuContext().dx());assertEquals(1,pcb(1).programCounter());
        assertEquals(ProcessState.READY,pcb(1).state());assertTrue(simulator.pendingKeyboardRequests().isEmpty());
        tick(2,TickResult.IN_PROGRESS,RuntimeStatus.RUNNABLE);tick(2,TickResult.PROGRAM_FINISHED,RuntimeStatus.RUNNABLE);
        tick(3,TickResult.PROGRAM_FINISHED,RuntimeStatus.RUNNABLE);tick(1,TickResult.PROGRAM_FINISHED,RuntimeStatus.FINISHED);
        assertEquals(42,simulator.completedProcesses().getLast().finalContext().dx());
    }
    @Test void fifoPausedValuesDrainBeforeNewInt09AndKeepRequestsUnique() {
        initialize();submit("p1","INT 09H","INC");submit("p2","INT 09H","INC");submit("p3","INT 09H");simulator.start();
        block(1,RuntimeStatus.RUNNABLE);block(2,RuntimeStatus.RUNNABLE);
        assertEquals(List.of(new PendingKeyboardRequest(1,0),new PendingKeyboardRequest(2,0)),simulator.pendingKeyboardRequests());
        simulator.pause();simulator.submitKeyboardInput(11);simulator.submitKeyboardInput(22);simulator.submitKeyboardInput(33);
        assertEquals(2,simulator.pendingKeyboardRequests().size());assertEquals(0,pcb(1).programCounter());
        simulator.resume();assertTrue(simulator.pendingKeyboardRequests().isEmpty());assertTrue(dispatcher().owner().isEmpty());
        assertEquals(List.of(3,1,2),ready().entries());assertEquals(11,pcb(1).cpuContext().dx());assertEquals(22,pcb(2).cpuContext().dx());
        tick(3,TickResult.IN_PROGRESS,RuntimeStatus.RUNNABLE);tick(3,TickResult.PROGRAM_FINISHED,RuntimeStatus.RUNNABLE);
        assertEquals(33,simulator.completedProcesses().getFirst().finalContext().dx());
        tick(1,TickResult.PROGRAM_FINISHED,RuntimeStatus.RUNNABLE);tick(2,TickResult.PROGRAM_FINISHED,RuntimeStatus.FINISHED);
    }
    @Test void allBlockedIdleHasNoTickAndTerminalInputCleansOnlyItsProcess() {
        initialize();submit("a","INT 09H");submit("b","INT 09H");simulator.start();
        block(1,RuntimeStatus.RUNNABLE);block(2,RuntimeStatus.WAITING_FOR_INPUT);
        var before=simulator.snapshot();for(int i=0;i<3;i++) assertEquals(new RuntimeStepResult.Idle(RuntimeStatus.WAITING_FOR_INPUT),simulator.step());
        assertEquals(before,simulator.snapshot());
        simulator.submitKeyboardInput(10);assertEquals(1,table().size());assertEquals(RuntimeStatus.WAITING_FOR_INPUT,simulator.runtimeStatus());
        simulator.submitKeyboardInput(20);assertEquals(SimulatorState.FINISHED,simulator.snapshot().simulatorState());
        assertEquals(List.of(10,20),simulator.completedProcesses().stream().map(r->r.finalContext().dx()).toList());
        assertTrue(simulator.snapshot().process().isEmpty());assertTrue(resources().entries().isEmpty());
    }
    @Test void suspendedInputOrdersByCompletionAndDoesNotEnterReadyUntilSwapIn() {
        initialize();submit("one","INT 09H","INC");submit("two","INT 09H","INC");submit("cpu","ADD AX");simulator.start();
        block(1,RuntimeStatus.RUNNABLE);simulator.swapOut(1);
        block(2,RuntimeStatus.RUNNABLE);simulator.swapOut(2);
        tick(3,TickResult.IN_PROGRESS,RuntimeStatus.RUNNABLE);var before=simulator.snapshot();
        simulator.submitKeyboardInput(7);simulator.submitKeyboardInput(8);
        assertEquals(before,simulator.snapshot());assertEquals(List.of(1,2),suspended().entries());assertTrue(ready().entries().isEmpty());
        assertEquals(ProcessState.READY_SUSPENDED,pcb(1).state());assertEquals(ProcessState.READY_SUSPENDED,pcb(2).state());
        tick(3,TickResult.IN_PROGRESS,RuntimeStatus.RUNNABLE);tick(3,TickResult.PROGRAM_FINISHED,RuntimeStatus.RUNNABLE);
        assertEquals(List.of(2),suspended().entries());assertEquals(List.of(1),ready().entries());
        tick(1,TickResult.PROGRAM_FINISHED,RuntimeStatus.RUNNABLE);tick(2,TickResult.PROGRAM_FINISHED,RuntimeStatus.FINISHED);
        assertTrue(suspended().entries().isEmpty());assertEquals(64,storage().allocateSwap(64).size());
    }
    @Test void terminalSuspendedInputCleansSwapAndKernelWithoutRestoringIt() {
        initialize();submit("input","INT 09H");submit("cpu","ADD AX");simulator.start();
        block(1,RuntimeStatus.RUNNABLE);simulator.swapOut(1);
        var r=resources().find(1).orElseThrow();var allocation=((UserImageResidence.Suspended)r.residence()).allocation();
        tick(2,TickResult.IN_PROGRESS,RuntimeStatus.RUNNABLE);var before=simulator.snapshot().cpu();
        simulator.submitKeyboardInput(99);
        assertEquals(before,simulator.snapshot().cpu());assertEquals(1,progress().consumedTicks());
        assertTrue(table().find(1).isEmpty());assertTrue(resources().find(1).isEmpty());
        assertEquals(99,simulator.completedProcesses().getFirst().finalContext().dx());
        assertThrows(RuntimeException.class,()->storage().releaseSwap(allocation));
        assertInstanceOf(EmptyContent.class,memory().read(r.address().address()));
        assertTrue(ready().entries().isEmpty());assertTrue(suspended().entries().isEmpty());
    }
    @Test void suspendedHeadCapacityFailureNeverSkipsSmallerCandidateOrPollsAdmission() {
        initialize();
        simulator.submitProgram(new ProgramImage("large",Collections.nCopies(50,new IncInstruction())));
        simulator.submitProgram(new ProgramImage("small",Collections.nCopies(10,new IncInstruction())));
        simulator.attemptNextAdmission();simulator.attemptNextAdmission();simulator.swapOut(1);simulator.swapOut(2);
        // A reserved region can be empty: allocator availability is the authority, not cell content.
        memory().allocateUser(80);simulator.start();
        assertEquals(List.of(1,2),suspended().entries());assertTrue(ready().entries().isEmpty());
        for(int i=0;i<3;i++) assertEquals(new RuntimeStepResult.Idle(RuntimeStatus.WAITING_FOR_CAPACITY),simulator.step());
        assertEquals(List.of(1,2),suspended().entries());assertEquals(SimulatorState.RUNNING,simulator.snapshot().simulatorState());
        assertTrue(simulator.completedProcesses().isEmpty());
    }
    @Test void suspendedArrivalOrderDiffersFromAdmissionAndExplicitSwapInRemovesMembership() {
        initialize();submit("one","INC");submit("two","INC");simulator.attemptNextAdmission();simulator.attemptNextAdmission();
        simulator.swapOut(2);simulator.swapOut(1);assertEquals(List.of(2,1),suspended().entries());
        simulator.swapIn(2);assertEquals(List.of(1),suspended().entries());
        simulator.start();tick(2,TickResult.PROGRAM_FINISHED,RuntimeStatus.RUNNABLE);tick(1,TickResult.PROGRAM_FINISHED,RuntimeStatus.FINISHED);
    }
    @Test void completionFreesFiveProcessCapacityAndAdmitsRemainingJobsInOrder() {
        initialize();for(int i=1;i<=7;i++) submit("p"+i,"INC");simulator.start();
        assertEquals(5,table().size());assertEquals(JobState.PENDING,simulator.jobs().get(5).state());
        var resource=resources().find(1).orElseThrow();
        tick(1,TickResult.PROGRAM_FINISHED,RuntimeStatus.RUNNABLE);
        assertEquals(5,table().size());assertEquals(JobState.ADMITTED,simulator.jobs().get(5).state());
        assertEquals(JobState.PENDING,simulator.jobs().get(6).state());assertEquals(List.of(2,3,4,5,6),ready().entries());
        assertThrows(RuntimeException.class,()->memory().release(resource.kernel()));
        for(int i=2;i<=7;i++) tick(i,TickResult.PROGRAM_FINISHED,i==7?RuntimeStatus.FINISHED:RuntimeStatus.RUNNABLE);
        assertEquals(7,simulator.completedProcesses().size());assertEquals(7,simulator.jobs().size());
        assertTrue(simulator.jobs().stream().allMatch(j->j.state()==JobState.ADMITTED));
    }
    @Test void suspendedProcessesStillCountAndResetDropsAllRuntimeOwnership() {
        initialize();for(int i=1;i<=6;i++) submit("p"+i,"INT 09H","INC");simulator.start();
        block(1,RuntimeStatus.RUNNABLE);simulator.swapOut(1);
        assertEquals(5,table().size());assertEquals(JobState.PENDING,simulator.jobs().getLast().state());
        tick(2,TickResult.IN_PROGRESS,RuntimeStatus.RUNNABLE);simulator.pause();simulator.submitKeyboardInput(4);
        simulator.reset();assertTrue(simulator.jobs().isEmpty());assertTrue(simulator.completedProcesses().isEmpty());
        assertTrue(simulator.pendingKeyboardRequests().isEmpty());assertTrue(simulator.snapshot().cpu().isEmpty());
        initialize();submit("clean","INT 09H");simulator.start();block(1,RuntimeStatus.WAITING_FOR_INPUT);
    }
    @Test void invariantMismatchRoutesToErrorInsteadOfFinished() {
        initialize();submit("p","INC");simulator.start();resources().remove(1);
        assertThrows(ExecutionEngineException.class,simulator::step);
        assertEquals(SimulatorState.ERROR,simulator.snapshot().simulatorState());assertTrue(simulator.completedProcesses().isEmpty());
    }
    @Test void compatibilityNamesAreDeterministicAndAvoidExplicitNameCollision() {
        initialize();submit("compatibility-program-1","INC");simulator.loadProgram(List.of(new IncInstruction()));
        assertEquals(List.of("compatibility-program-1","compatibility-program-2"),storage().entries().stream().map(e->e.name()).toList());
        assertEquals(2,simulator.jobs().size());simulator.reset();initialize();simulator.loadProgram(List.of(new IncInstruction()));
        assertEquals("compatibility-program-1",storage().entries().getFirst().name());
    }
}
