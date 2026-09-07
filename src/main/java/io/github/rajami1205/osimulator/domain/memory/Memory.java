package io.github.rajami1205.osimulator.domain.memory;

import io.github.rajami1205.osimulator.domain.memory.exception.InvalidMemoryAddressException;
import io.github.rajami1205.osimulator.domain.memory.exception.MemoryProtectionException;
import java.util.List;
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

    public void writeUser(int address, T value) {
        validateUserAddress(address);
        T nonNullValue = Objects.requireNonNull(value, "value must not be null");
        positions[address] = nonNullValue;
    }

    public void writeUserBlock(int startAddress, List<? extends T> values) {
        validateUserAddress(startAddress);
        List<? extends T> snapshot = List.copyOf(
                Objects.requireNonNull(values, "values must not be null")
        );
        validateBlockRange(startAddress, snapshot.size());

        int address = startAddress;
        for (T value : snapshot) {
            positions[address] = value;
            address++;
        }
    }

    private void validateUserAddress(int address) {
        if (regionOf(address) == MemoryRegion.KERNEL) {
            throw new MemoryProtectionException(
                    "User write cannot modify Kernel memory address: " + address
            );
        }
    }

    private void validateBlockRange(int startAddress, int blockSize) {
        if (blockSize > size() - startAddress) {
            throw new InvalidMemoryAddressException(
                    "Memory block exceeds the configured address space"
            );
        }
    }

    @SuppressWarnings("unchecked")
    private T valueAt(int address) {
        configuration.regionOf(address);
        return (T) positions[address];
    }
}
