package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.memory.exception.InvalidMemoryAddressException;
import io.github.rajami1205.osimulator.model.memory.exception.MemoryProtectionException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Proporciona almacenamiento genérico de tamaño fijo para la memoria simulada.
 *
 * @param <T> tipo de contenido almacenado en las posiciones de memoria
 */
public class Memory<T> {

    private final MemoryConfiguration configuration;
    private final Object[] positions;

    // Reserva el almacenamiento fijo definido por la configuración de memoria.
    public Memory(MemoryConfiguration configuration) {
        this.configuration = Objects.requireNonNull(configuration, "configuration must not be null");
        this.positions = new Object[this.configuration.totalPositions()];
    }

    // Expone la configuración inmutable de esta memoria.
    public MemoryConfiguration configuration() {
        return configuration;
    }

    // Expone la cantidad fija de posiciones de la sesión.
    public int size() {
        return configuration.totalPositions();
    }

    // Determina la región Kernel o User de una dirección válida.
    public MemoryRegion regionOf(int address) {
        return configuration.regionOf(address);
    }

    // Consulta el contenido opcional de una posición válida.
    public Optional<T> read(int address) {
        return Optional.ofNullable(valueAt(address));
    }

    // Indica si una posición válida carece de contenido.
    public boolean isEmpty(int address) {
        return valueAt(address) == null;
    }

    // Escribe contenido no nulo en una posición protegida por las reglas User.
    public void writeUser(int address, T value) {
        validateUserAddress(address);
        T nonNullValue = Objects.requireNonNull(value, "value must not be null");
        positions[address] = nonNullValue;
    }

    // Valida el bloque completo antes de escribir sus elementos en posiciones consecutivas.
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

    // Vacía la región User conservando la reserva Kernel.
    public void clearUserSpace() {
        Arrays.fill(positions, configuration.userStartAddress(), size(), null);
    }

    // Impide escrituras de usuario en la región Kernel.
    private void validateUserAddress(int address) {
        if (regionOf(address) == MemoryRegion.KERNEL) {
            throw new MemoryProtectionException(
                    "User write cannot modify Kernel memory address: " + address
            );
        }
    }

    // Comprueba que el bloque quepa desde su dirección inicial.
    private void validateBlockRange(int startAddress, int blockSize) {
        if (blockSize > size() - startAddress) {
            throw new InvalidMemoryAddressException(
                    "Memory block exceeds the configured address space"
            );
        }
    }

    @SuppressWarnings("unchecked")
    // Valida la dirección antes de consultar el almacenamiento interno.
    private T valueAt(int address) {
        configuration.regionOf(address);
        return (T) positions[address];
    }
}
