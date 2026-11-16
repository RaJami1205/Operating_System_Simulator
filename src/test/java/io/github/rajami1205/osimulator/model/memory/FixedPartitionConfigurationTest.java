package io.github.rajami1205.osimulator.model.memory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FixedPartitionConfigurationTest {
    private final MemoryConfiguration geometry = new MemoryConfiguration(128, 32);

    @Test void equalCoversUserExactlyAndAllowsMoreThanTwentyFivePartitions() {
        assertEquals(List.of(new FixedPartition(1, 32, 32), new FixedPartition(2, 64, 32),
                new FixedPartition(3, 96, 32)), FixedPartitionConfiguration.equal(3).layout(geometry));
        var cells = FixedPartitionConfiguration.equal(96).layout(geometry);
        assertEquals(96, cells.size()); assertEquals(new FixedPartition(96, 127, 1), cells.getLast());
        assertThrows(UnsupportedOperationException.class, () -> cells.clear());
    }

    @ParameterizedTest @ValueSource(ints = {-1, 0, 5, 97, Integer.MAX_VALUE})
    void rejectsInvalidEqualCounts(int count) {
        assertThrows(IllegalArgumentException.class, () -> FixedPartitionConfiguration.equal(count).layout(geometry));
    }

    @Test void unequalPreservesOrderRepeatedCapacitiesAndCopiesInput() {
        var source = new ArrayList<>(List.of(32, 16, 16, 32));
        var configuration = FixedPartitionConfiguration.unequal(source);
        source.set(0, 1);
        assertEquals(List.of(new FixedPartition(1, 32, 32), new FixedPartition(2, 64, 16),
                new FixedPartition(3, 80, 16), new FixedPartition(4, 96, 32)), configuration.layout(geometry));
        assertThrows(UnsupportedOperationException.class, () -> configuration.capacities().clear());
    }

    @Test void rejectsInvalidUnequalSumsSizesOverflowAndModeCombinations() {
        for (var sizes : List.of(List.<Integer>of(), List.of(0, 96), List.of(-1, 97), List.of(95),
                List.of(97), List.of(Integer.MAX_VALUE, 1))) {
            assertThrows(IllegalArgumentException.class, () -> FixedPartitionConfiguration.unequal(sizes).layout(geometry));
        }
        assertThrows(NullPointerException.class, () -> FixedPartitionConfiguration.unequal(java.util.Arrays.asList(32, null)));
        assertThrows(IllegalArgumentException.class, () -> new FixedPartitionConfiguration(MemoryMode.DYNAMIC, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new FixedPartitionConfiguration(MemoryMode.FIXED_EQUAL, 1, List.of(96)));
        assertThrows(IllegalArgumentException.class, () -> new FixedPartitionConfiguration(MemoryMode.FIXED_UNEQUAL, 2, List.of(96)));
        assertThrows(IllegalArgumentException.class, () -> FixedPartitionConfiguration.validateMode(geometry, MemoryMode.FIXED_EQUAL, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> FixedPartitionConfiguration.validateMode(geometry, MemoryMode.DYNAMIC, Optional.of(FixedPartitionConfiguration.equal(3))));
        assertThrows(IllegalArgumentException.class, () -> FixedPartitionConfiguration.validateMode(geometry, MemoryMode.FIXED_UNEQUAL, Optional.of(FixedPartitionConfiguration.equal(3))));
    }

    @Test void derivedAddressesAtIntegerBoundaryRemainExact() {
        var high = new MemoryConfiguration(Integer.MAX_VALUE, Integer.MAX_VALUE - 10);
        assertEquals(new FixedPartition(2, Integer.MAX_VALUE - 5, 5), FixedPartitionConfiguration.equal(2).layout(high).getLast());
        assertThrows(IllegalArgumentException.class, () -> new FixedPartition(1, Integer.MAX_VALUE, 1));
    }
}
