package io.github.rajami1205.osimulator.model.memory;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryAddressException;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException;
import java.util.Arrays;
import java.util.List;
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

    @Test
    void shouldWriteUserBlockFromInclusiveStartAddress() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        memory.writeUserBlock(32, List.of("A", "B", "C"));

        assertAll(
                () -> assertEquals("A", memory.read(32).orElseThrow()),
                () -> assertEquals("B", memory.read(33).orElseThrow()),
                () -> assertEquals("C", memory.read(34).orElseThrow()),
                () -> assertFalse(memory.isEmpty(32)),
                () -> assertFalse(memory.isEmpty(33)),
                () -> assertFalse(memory.isEmpty(34))
        );
    }

    @Test
    void shouldWriteBlockEndingAtLastMemoryAddress() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        memory.writeUserBlock(125, List.of("A", "B", "C"));

        assertAll(
                () -> assertEquals("A", memory.read(125).orElseThrow()),
                () -> assertEquals("B", memory.read(126).orElseThrow()),
                () -> assertEquals("C", memory.read(127).orElseThrow())
        );
    }

    @Test
    void shouldOverwriteExistingValuesWithUserBlock() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));
        memory.writeUser(32, "old A");
        memory.writeUser(33, "old B");

        memory.writeUserBlock(32, List.of("new A", "new B"));

        assertAll(
                () -> assertEquals("new A", memory.read(32).orElseThrow()),
                () -> assertEquals("new B", memory.read(33).orElseThrow())
        );
    }

    @Test
    void shouldAcceptEmptyBlockWithoutModifyingMemory() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));
        memory.writeUser(32, "original");

        assertDoesNotThrow(() -> memory.writeUserBlock(32, List.of()));

        assertAll(
                () -> assertEquals("original", memory.read(32).orElseThrow()),
                () -> assertTrue(memory.isEmpty(33))
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 128})
    void shouldRejectInvalidBlockStartEvenWhenBlockIsEmpty(int startAddress) {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        assertThrows(
                InvalidMemoryAddressException.class,
                () -> memory.writeUserBlock(startAddress, List.of())
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 31})
    void shouldProtectKernelFromBlockWritesEvenWhenBlockIsEmpty(int startAddress) {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));
        memory.writeUser(32, "user value");

        assertThrows(
                MemoryProtectionException.class,
                () -> memory.writeUserBlock(startAddress, List.of())
        );

        assertAll(
                () -> assertTrue(memory.read(startAddress).isEmpty()),
                () -> assertEquals("user value", memory.read(32).orElseThrow())
        );
    }

    @Test
    void shouldRejectNullBlockWithoutModifyingMemory() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));
        memory.writeUser(32, "original");

        assertThrows(NullPointerException.class, () -> memory.writeUserBlock(32, null));

        assertEquals("original", memory.read(32).orElseThrow());
    }

    @Test
    void shouldRejectNullBlockElementBeforeWritingAnyValue() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));
        List<String> values = Arrays.asList("A", null, "C");

        assertThrows(NullPointerException.class, () -> memory.writeUserBlock(32, values));

        assertAll(
                () -> assertTrue(memory.isEmpty(32)),
                () -> assertTrue(memory.isEmpty(33)),
                () -> assertTrue(memory.isEmpty(34))
        );
    }

    @Test
    void shouldPreserveExistingValuesWhenBlockExceedsMemory() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));
        memory.writeUser(126, "original A");
        memory.writeUser(127, "original B");

        assertThrows(
                InvalidMemoryAddressException.class,
                () -> memory.writeUserBlock(126, List.of("new A", "new B", "overflow"))
        );

        assertAll(
                () -> assertEquals("original A", memory.read(126).orElseThrow()),
                () -> assertEquals("original B", memory.read(127).orElseThrow())
        );
    }

    @Test
    void shouldClearIndividualWritesAcrossUserSpaceAndPreserveLayout() {
        MemoryConfiguration configuration = new MemoryConfiguration(128, 32);
        Memory<String> memory = new Memory<>(configuration);
        memory.writeUser(32, "first");
        memory.writeUser(80, "middle");
        memory.writeUser(127, "last");

        memory.clearUserSpace();

        assertAll(
                () -> assertTrue(memory.read(32).isEmpty()),
                () -> assertTrue(memory.isEmpty(32)),
                () -> assertTrue(memory.read(80).isEmpty()),
                () -> assertTrue(memory.isEmpty(80)),
                () -> assertTrue(memory.read(127).isEmpty()),
                () -> assertTrue(memory.isEmpty(127)),
                () -> assertSame(configuration, memory.configuration()),
                () -> assertEquals(128, memory.size()),
                () -> assertEquals(MemoryRegion.KERNEL, memory.regionOf(0)),
                () -> assertEquals(MemoryRegion.KERNEL, memory.regionOf(31))
        );
    }

    @Test
    void shouldClearValuesWrittenAsBlock() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));
        memory.writeUserBlock(32, List.of("A", "B", "C"));

        memory.clearUserSpace();

        assertAll(
                () -> assertTrue(memory.isEmpty(32)),
                () -> assertTrue(memory.isEmpty(33)),
                () -> assertTrue(memory.isEmpty(34))
        );
    }

    @Test
    void shouldClearEntireSmallUserSpaceUsingConfiguredBoundaries() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 126));
        memory.writeUser(126, "first");
        memory.writeUser(127, "last");

        memory.clearUserSpace();

        assertAll(
                () -> assertTrue(memory.isEmpty(126)),
                () -> assertTrue(memory.isEmpty(127)),
                () -> assertEquals(MemoryRegion.KERNEL, memory.regionOf(125))
        );
    }

    @Test
    void shouldClearUserSpaceWithSinglePosition() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 127));
        memory.writeUser(127, "value");

        memory.clearUserSpace();

        assertAll(
                () -> assertTrue(memory.read(127).isEmpty()),
                () -> assertTrue(memory.isEmpty(127)),
                () -> assertEquals(MemoryRegion.KERNEL, memory.regionOf(126))
        );
    }

    @Test
    void shouldClearAlreadyEmptyUserSpace() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));

        assertDoesNotThrow(memory::clearUserSpace);
        assertTrue(memory.isEmpty(32));
    }

    @Test
    void shouldClearUserSpaceIdempotently() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));
        memory.writeUser(32, "value");

        memory.clearUserSpace();
        memory.clearUserSpace();

        assertTrue(memory.isEmpty(32));
    }

    @Test
    void shouldAllowUserWritesAfterClearing() {
        Memory<String> memory = new Memory<>(new MemoryConfiguration(128, 32));
        memory.writeUser(32, "old value");
        memory.clearUserSpace();

        memory.writeUser(32, "new value");

        assertAll(
                () -> assertEquals("new value", memory.read(32).orElseThrow()),
                () -> assertFalse(memory.isEmpty(32))
        );
    }
}
