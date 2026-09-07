package io.github.rajami1205.osimulator.domain.memory;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.rajami1205.osimulator.domain.memory.exception.InvalidMemoryConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MemoryConfigurationTest {

    @Test
    void shouldAllowMinimumTotalPositions() {
        MemoryConfiguration configuration = new MemoryConfiguration(128, 32);

        assertEquals(128, configuration.totalPositions());
    }

    @Test
    void shouldAllowMoreThanMinimumTotalPositions() {
        MemoryConfiguration configuration = new MemoryConfiguration(256, 64);

        assertEquals(256, configuration.totalPositions());
    }

    @Test
    void shouldAllowOneKernelPosition() {
        MemoryConfiguration configuration = new MemoryConfiguration(128, 1);

        assertEquals(1, configuration.kernelReservedPositions());
    }

    @Test
    void shouldAllowExactlyOneUserPosition() {
        MemoryConfiguration configuration = new MemoryConfiguration(128, 127);

        assertEquals(1, configuration.userPositions());
    }

    @Test
    void shouldExposeCanonicalAndDerivedValues() {
        MemoryConfiguration configuration = new MemoryConfiguration(128, 32);

        assertAll(
                () -> assertEquals(128, configuration.totalPositions()),
                () -> assertEquals(32, configuration.kernelReservedPositions()),
                () -> assertEquals(32, configuration.userStartAddress()),
                () -> assertEquals(96, configuration.userPositions())
        );
    }

    @ParameterizedTest
    @ValueSource(ints = {127, 0, -1})
    void shouldRejectTotalPositionsBelowMinimum(int totalPositions) {
        assertInvalidConfiguration(totalPositions, 1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void shouldRejectNonPositiveKernelPositions(int kernelReservedPositions) {
        assertInvalidConfiguration(128, kernelReservedPositions);
    }

    @Test
    void shouldRejectKernelPositionsEqualToTotalPositions() {
        assertInvalidConfiguration(128, 128);
    }

    @Test
    void shouldRejectKernelPositionsGreaterThanTotalPositions() {
        assertInvalidConfiguration(128, 129);
    }

    private void assertInvalidConfiguration(int totalPositions, int kernelReservedPositions) {
        InvalidMemoryConfigurationException exception = assertThrows(
                InvalidMemoryConfigurationException.class,
                () -> new MemoryConfiguration(totalPositions, kernelReservedPositions)
        );

        assertNotNull(exception.getMessage());
        assertFalse(exception.getMessage().isBlank());
    }
}
