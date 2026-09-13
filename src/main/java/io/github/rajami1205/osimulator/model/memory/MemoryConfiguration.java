package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryAddressException;
import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryConfigurationException;

/**
 * Define la configuración inmutable de la distribución de memoria simulada.
 *
 * @param totalPositions cantidad total de posiciones de memoria simulada
 * @param kernelReservedPositions cantidad de posiciones reservadas para el Kernel
 */
// Valida el mínimo de memoria y una reserva Kernel que deje espacio User.
public record MemoryConfiguration(int totalPositions, int kernelReservedPositions) {

    // Valida el mínimo de memoria y una reserva Kernel que deje espacio User.
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

    // Obtiene la primera posición posterior a la reserva Kernel.
    public int userStartAddress() {
        return kernelReservedPositions;
    }

    // Calcula la capacidad disponible para programas de usuario.
    public int userPositions() {
        return totalPositions - kernelReservedPositions;
    }

    // Determina la región Kernel o User de una dirección válida.
    public MemoryRegion regionOf(int address) {
        validateAddress(address);
        return address < kernelReservedPositions ? MemoryRegion.KERNEL : MemoryRegion.USER;
    }

    // Rechaza direcciones fuera del espacio de memoria configurado.
    private void validateAddress(int address) {
        if (address < 0 || address >= totalPositions) {
            throw new InvalidMemoryAddressException(
                    "Memory address must be between 0 and " + (totalPositions - 1) + ": " + address
            );
        }
    }
}
