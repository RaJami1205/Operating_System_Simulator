package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.program.ProgramLoader;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.execution.ExecutionEngine;
import io.github.rajami1205.osimulator.model.instruction.IncInstruction;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.memory.exception.IncompatibleProgramException;
import io.github.rajami1205.osimulator.model.program.ProgramImage;
import io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId;
import io.github.rajami1205.osimulator.testing.ControlledClock;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FixedPartitionSessionTest {
    private SimulatorOrchestrator simulator() {
        return new SimulatorOrchestrator(new ProgramLoader(), new ExecutionEngine(), new ControlledClock());
    }
    private ProgramImage program(String name, int length) {
        return new ProgramImage(name, Collections.nCopies(length, new IncInstruction()));
    }
    private SimulatorConfiguration configuration(int count, MemoryMode mode) {
        int kernel = count * 140;
        var fixed = mode == MemoryMode.FIXED_EQUAL ? FixedPartitionConfiguration.equal(count)
                : FixedPartitionConfiguration.unequal(Collections.nCopies(count, 120));
        return new SimulatorConfiguration(new MemoryConfiguration(kernel + count * 120, kernel), 2048, 256,
                count, SchedulingPolicyId.FCFS, UserAllocationStrategy.NEXT_FIT, mode, Optional.of(fixed));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5})
    void sharedPartitionsPreserveAffinityClockArrivalsAndCompletionAcrossCpus(int count) {
        for (var mode : List.of(MemoryMode.FIXED_EQUAL, MemoryMode.FIXED_UNEQUAL)) {
            var run = simulator(); run.initialize(configuration(count, mode));
            for (int i = 0; i < count; i++) run.submitProgram(program("first" + i, 2));
            run.submitProgram(program("waiting", 1));
            run.submitProgram(program("future", 1), 5);
            run.start();
            var initial = run.snapshot();
            assertEquals(count, initial.partitions().size());
            assertThrows(UnsupportedOperationException.class, () -> initial.partitions().clear());
            for (int i = 0; i < count; i++) {
                var p = initial.partitions().get(i);
                assertEquals(i + 1, p.partitionId()); assertEquals(count * 140 + i * 120, p.base());
                assertEquals(120, p.capacity()); assertTrue(p.occupied());
                assertEquals(Optional.of(i + 1), p.ownerPid()); assertEquals(2, p.usedLength());
                assertEquals(118, p.internalFragmentation());
                assertEquals(i, initial.processes().get(i).cpuAffinity().value());
                assertEquals(2, initial.processes().get(i).limit());
            }
            run.step();
            assertEquals(1, run.snapshot().simulationTimeSeconds().orElseThrow());
            assertTrue(run.snapshot().cpus().stream().allMatch(cpu -> cpu.cpuTicks() == 1));
            for (int guard = 0; guard < 10 && run.snapshot().simulatorState() != SimulatorState.FINISHED; guard++) run.step();
            var done = run.snapshot();
            assertEquals(SimulatorState.FINISHED, done.simulatorState());
            assertEquals(6, done.simulationTimeSeconds().orElseThrow());
            assertEquals(count + 2, done.completedProcesses().size());
            assertTrue(done.processes().isEmpty());
            assertTrue(done.partitions().stream().allMatch(p -> !p.occupied() && p.ownerPid().isEmpty()
                    && p.usedLength() == 0 && p.internalFragmentation() == 0));
            assertEquals(2 * count + 2, done.cpus().stream().mapToLong(SimulatorSnapshot.CpuView::cpuTicks).sum());
            assertTrue(initial.partitions().stream().allMatch(SimulatorSnapshot.PartitionView::occupied));
            run.reset(); assertTrue(run.snapshot().partitions().isEmpty());
            run.initialize(SimulatorConfiguration.defaults()); assertTrue(run.snapshot().partitions().isEmpty());
            assertEquals(MemoryMode.DYNAMIC, run.configuration().orElseThrow().memoryMode());
        }
    }

    @Test void publicLoadAndSubmitRejectImpossibleBeforePublishingAnything() {
        var run = simulator(); run.initialize(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 512, 64,
                1, SchedulingPolicyId.FCFS, UserAllocationStrategy.FIRST_FIT, MemoryMode.FIXED_EQUAL,
                Optional.of(FixedPartitionConfiguration.equal(4))));
        var before = run.snapshot();
        assertThrows(IncompatibleProgramException.class, () -> run.submitProgram(program("impossible", 30), 100));
        assertThrows(IncompatibleProgramException.class, () -> run.loadProgram(program("legacy", 30).instructions()));
        assertEquals(before, run.snapshot());
        assertEquals(1, run.submitProgram(program("valid", 1)).jobId());
        run.start(); run.step();
        assertEquals(SimulatorState.FINISHED, run.snapshot().simulatorState());
    }
}
