package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.memory.MemoryAllocation;
import io.github.rajami1205.osimulator.model.memory.MemoryRegion;
import io.github.rajami1205.osimulator.model.process.PcbAddress;
import java.util.Objects;

/** Asocia la reserva Kernel y dirección simulada del PCB con una única residencia de su imagen USER. */
public record ProcessResources(MemoryAllocation kernel, PcbAddress address, UserImageResidence residence) {
    /**
     * Exige dependencias no nulas y una reserva Kernel de una posición coincidente con la dirección del
     * PCB.
     */
    public ProcessResources {
        Objects.requireNonNull(kernel);
        Objects.requireNonNull(address);
        Objects.requireNonNull(residence);
        if (kernel.region() != MemoryRegion.KERNEL || kernel.size() != 1 || address.address() != kernel.base()) {
            throw new IllegalArgumentException("PCB address must match its Kernel allocation");
        }
    }
    /** Crea recursos con la nueva residencia conservando la misma reserva y dirección Kernel del PCB. */
    public ProcessResources withResidence(UserImageResidence residence) {
        return new ProcessResources(kernel, address, residence);
    }
}
