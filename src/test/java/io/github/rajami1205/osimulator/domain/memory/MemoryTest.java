package io.github.rajami1205.osimulator.domain.memory;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rajami1205.osimulator.domain.memory.exception.InvalidMemoryAddressException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class MemoryTest {

    @Test
    void shouldExposeItsConfigurationAndSize() {
        MemoryConfiguration configuration = new MemoryConfiguration(128, 32);
        Memory<String> memory = new Memory<>(configuration);

        assertAll(
                () -> assertSame(configuration, memory.configuration()),
                () -> assertEquals(128, memory.size())
        );
    }

    @Test
    void shouldRejectNullConfiguration() {
        assertThrows(NullPointerException.class, () -> new Memory<String>(null));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 31, 32, 127})
    void shouldStartWithEmptyPositions(int address) {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        assertAll(
                () -> assertTrue(memory.isEmpty(address)),
                () -> assertTrue(memory.read(address).isEmpty())
        );
    }

    @ParameterizedTest
    @CsvSource({
            "0, KERNEL",
            "31, KERNEL",
            "32, USER",
            "127, USER"
    })
    void shouldDelegateRegionClassification(int address, MemoryRegion expectedRegion) {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        assertEquals(expectedRegion, memory.regionOf(address));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 128})
    void shouldRejectInvalidAddressesWhenReading(int address) {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        assertThrows(InvalidMemoryAddressException.class, () -> memory.read(address));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 128})
    void shouldRejectInvalidAddressesWhenCheckingIfEmpty(int address) {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        assertThrows(InvalidMemoryAddressException.class, () -> memory.isEmpty(address));
    }
}
