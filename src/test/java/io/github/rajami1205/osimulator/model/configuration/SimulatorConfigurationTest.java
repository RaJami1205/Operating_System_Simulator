package io.github.rajami1205.osimulator.model.configuration;

import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import io.github.rajami1205.osimulator.model.scheduling.SchedulingPolicyId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class SimulatorConfigurationTest {
    @Test void historicalConstructorsAndExplicitPolicyAreEquivalent() {
        var memory = new MemoryConfiguration(256, 140);
        var explicit = new SimulatorConfiguration(memory, 512, 64, 1, SchedulingPolicyId.FCFS);
        assertEquals(SimulatorConfiguration.defaults(), explicit);
        assertEquals(new SimulatorConfiguration(memory, 512, 64), explicit);
        assertEquals(new SimulatorConfiguration(memory, 512, 64, 1), explicit);
        assertEquals(SchedulingPolicyId.FCFS, explicit.schedulingPolicy());
        assertThrows(NullPointerException.class, () -> new SimulatorConfiguration(memory, 512, 64, 1, null));
        assertEquals(java.util.List.of(SchedulingPolicyId.FCFS), java.util.List.of(SchedulingPolicyId.values()));
        assertThrows(IllegalArgumentException.class, () -> SchedulingPolicyId.valueOf("RR"));
    }
    @ParameterizedTest
    @CsvSource({"1,5,140", "2,10,280", "3,15,420", "4,20,560", "5,25,700"})
    void validatesCapacityAndExactKernelBoundary(int cpus, int capacity, int kernel) {
        int main = Math.max(256, kernel + 1);
        var configuration = new SimulatorConfiguration(new MemoryConfiguration(main, kernel), 512, 64, cpus);
        assertEquals(cpus, configuration.cpuCount());
        assertEquals(capacity, configuration.maxProcesses());
        assertEquals(kernel, configuration.minimumKernelMemory());
        assertThrows(IllegalArgumentException.class, () -> new SimulatorConfiguration(
                new MemoryConfiguration(main, kernel - 1), 512, 64, cpus));
        assertThrows(IllegalArgumentException.class, () -> new SimulatorConfiguration(
                new MemoryConfiguration(kernel, kernel), 512, 64, cpus));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 6})
    void rejectsInvalidCpuCount(int cpus) {
        assertThrows(IllegalArgumentException.class, () -> new SimulatorConfiguration(
                new MemoryConfiguration(1024, 700), 512, 64, cpus));
    }

    @Test
    void exposesApprovedDefaults() {
        var configuration = SimulatorConfiguration.defaults();
        assertEquals(1, configuration.cpuCount());
        assertEquals(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 512, 64, 1), configuration);
        assertEquals(new SimulatorConfiguration(new MemoryConfiguration(256, 140), 512, 64), configuration);
        assertEquals(new MemoryConfiguration(256, 140), configuration.mainMemory());
        assertEquals(512, configuration.secondaryStoragePositions());
        assertEquals(64, configuration.virtualMemoryPositions());
    }

    @Test
    void composesMemoryWithoutPresentationMaximums() {
        var memory = new MemoryConfiguration(4096, 4095);
        var configuration = new SimulatorConfiguration(memory, 8192, 8191);
        assertSame(memory, configuration.mainMemory());
        assertEquals(8192, configuration.secondaryStoragePositions());
        assertEquals(8191, configuration.virtualMemoryPositions());
        assertEquals(new SimulatorConfiguration(memory, 8192, 8191), configuration);
    }

    @Test
    void acceptsMinimumStorageAndVirtualMemory() {
        var configuration = new SimulatorConfiguration(new MemoryConfiguration(256, 140), 128, 64);
        assertEquals(128, configuration.secondaryStoragePositions());
        assertEquals(64, configuration.virtualMemoryPositions());
    }

    @Test
    void rejectsMissingMemory() {
        assertThrows(NullPointerException.class, () -> new SimulatorConfiguration(null, 512, 64));
    }

    @ParameterizedTest
    @CsvSource({"127,64", "0,64", "-1,64", "128,63", "512,0", "512,-1",
            "128,128", "128,129", "512,512", "512,513"})
    void rejectsInvalidCapacities(int secondary, int virtual) {
        assertThrows(IllegalArgumentException.class,
                () -> new SimulatorConfiguration(new MemoryConfiguration(256, 140), secondary, virtual));
    }
    @ParameterizedTest
    @CsvSource({"255,140", "256,139", "256,256", "256,257"})
    void rejectsInvalidSessionMemory(int main,int kernel) {
        assertThrows(IllegalArgumentException.class, () ->
                new SimulatorConfiguration(new MemoryConfiguration(main,kernel),512,64));
    }

    @ParameterizedTest
    @CsvSource({"256,140", "257,140", "256,141", "256,255", "512,140", "512,300"})
    void acceptsSessionBoundariesWithoutExtraUserMinimum(int main,int kernel) {
        var configuration=new SimulatorConfiguration(new MemoryConfiguration(main,kernel),512,64);
        assertEquals(main-kernel,configuration.mainMemory().userPositions());
    }

}
