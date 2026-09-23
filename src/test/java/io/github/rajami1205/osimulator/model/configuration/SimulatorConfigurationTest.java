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
        assertEquals(new MemoryConfiguration(256, 32), configuration.mainMemory());
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
        assertDoesNotThrow(() -> new SimulatorConfiguration(memory, 2, 1));
    }

    @Test
    void rejectsMissingMemory() {
        assertThrows(NullPointerException.class, () -> new SimulatorConfiguration(null, 512, 64));
    }

    @ParameterizedTest
    @CsvSource({"0,1", "-1,1", "512,0", "512,-1", "512,512", "512,513"})
    void rejectsInvalidCapacities(int secondary, int virtual) {
        assertThrows(IllegalArgumentException.class,
                () -> new SimulatorConfiguration(new MemoryConfiguration(256, 32), secondary, virtual));
    }
}
