package io.github.rajami1205.osimulator.domain.memory;

import io.github.rajami1205.osimulator.domain.memory.exception.InvalidMemoryAddressException;
import io.github.rajami1205.osimulator.domain.memory.exception.InvalidMemoryConfigurationException;

/**
 * Immutable configuration for the simulated memory layout.
 *
 * @param totalPositions total number of simulated memory positions
 * @param kernelReservedPositions number of positions reserved for the Kernel
 */
public record MemoryConfiguration(int totalPositions, int kernelReservedPositions) {

    public MemoryConfiguration {
        if (totalPositions < 128) {
            throw new InvalidMemoryConfigurationException(
                    "Total memory must contain at least 128 simulated positions: " + totalPositions
            );
        }

        if (kernelReservedPositions <= 0) {
            throw new InvalidMemoryConfigurationException(
                    "Kernel reserved positions must be greater than zero: " + kernelReservedPositions
            );
        }

        if (kernelReservedPositions >= totalPositions) {
            throw new InvalidMemoryConfigurationException(
                    "Kernel reserved positions must be less than total memory positions"
            );
        }
    }

    public int userStartAddress() {
        return kernelReservedPositions;
    }

    public int userPositions() {
        return totalPositions - kernelReservedPositions;
    }

    public MemoryRegion regionOf(int address) {
        validateAddress(address);
        return address < kernelReservedPositions ? MemoryRegion.KERNEL : MemoryRegion.USER;
    }

    private void validateAddress(int address) {
        if (address < 0 || address >= totalPositions) {
            throw new InvalidMemoryAddressException(
                    "Memory address must be between 0 and " + (totalPositions - 1) + ": " + address
            );
        }
    }
}
