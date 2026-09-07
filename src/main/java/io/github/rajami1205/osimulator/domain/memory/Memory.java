package io.github.rajami1205.osimulator.domain.memory;

import java.util.Objects;
import java.util.Optional;

/**
 * Fixed-size, generic storage for the simulated memory address space.
 *
 * @param <T> type of content stored in memory positions
 */
public class Memory<T> {

    private final MemoryConfiguration configuration;
    private final Object[] positions;

    public Memory(MemoryConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.positions = new Object[this.configuration.totalPositions()];
    }

    public MemoryConfiguration configuration() {
        return configuration;
    }

    public int size() {
        return configuration.totalPositions();
    }

    public MemoryRegion regionOf(int address) {
        return configuration.regionOf(address);
    }

    public Optional<T> read(int address) {
        return Optional.ofNullable(valueAt(address));
    }

    public boolean isEmpty(int address) {
        return valueAt(address) == null;
    }

    @SuppressWarnings("unchecked")
    private T valueAt(int address) {
        configuration.regionOf(address);
        return (T) positions[address];
    }
}
