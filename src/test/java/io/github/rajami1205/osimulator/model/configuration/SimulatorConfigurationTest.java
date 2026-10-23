package io.github.rajami1205.osimulator.model.configuration;

import io.github.rajami1205.osimulator.model.memory.MemoryConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class SimulatorConfigurationTest {
    @Test
    void exposesApprovedDefaults() {
        var configuration = SimulatorConfiguration.defaults();
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
