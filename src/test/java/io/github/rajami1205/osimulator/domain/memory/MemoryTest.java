package io.github.rajami1205.osimulator.domain.memory;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rajami1205.osimulator.domain.memory.exception.InvalidMemoryAddressException;
import io.github.rajami1205.osimulator.domain.memory.exception.MemoryProtectionException;
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

    @ParameterizedTest
    @ValueSource(ints = {32, 127})
    void shouldWriteToUserPositions(int address) {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        memory.writeUser(address, "value");

        assertAll(
                () -> assertEquals("value", memory.read(address).orElseThrow()),
                () -> assertFalse(memory.isEmpty(address))
        );
    }

    @Test
    void shouldOverwriteExistingUserValue() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        memory.writeUser(32, "first");
        memory.writeUser(32, "second");

        assertEquals("second", memory.read(32).orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 31})
    void shouldProtectKernelPositionsFromUserWrites(int address) {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        assertThrows(MemoryProtectionException.class, () -> memory.writeUser(address, "value"));
        assertTrue(memory.read(address).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 128})
    void shouldRejectInvalidAddressesWhenWriting(int address) {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        assertThrows(InvalidMemoryAddressException.class, () -> memory.writeUser(address, "value"));
    }

    @Test
    void shouldRejectNullUserValueWithoutModifyingEmptyPosition() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        assertThrows(NullPointerException.class, () -> memory.writeUser(32, null));
        assertTrue(memory.read(32).isEmpty());
    }

    @Test
    void shouldPreserveExistingValueAfterFailedOverwrite() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));
        memory.writeUser(32, "original");

        assertThrows(NullPointerException.class, () -> memory.writeUser(32, null));

        assertEquals("original", memory.read(32).orElseThrow());
    }

    @Test
    void shouldValidateAddressBeforeNullValue() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        assertThrows(InvalidMemoryAddressException.class, () -> memory.writeUser(-1, null));
    }

    @Test
    void shouldValidateKernelProtectionBeforeNullValue() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        assertThrows(MemoryProtectionException.class, () -> memory.writeUser(0, null));
    }
}
