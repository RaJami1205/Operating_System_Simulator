package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.memory.MemoryAllocation;
import io.github.rajami1205.osimulator.model.memory.MemoryRegion;
import io.github.rajami1205.osimulator.model.process.PcbAddress;
import java.util.Objects;

public record ProcessResources(MemoryAllocation kernel, PcbAddress address, UserImageResidence residence) {
    public ProcessResources {
        Objects.requireNonNull(kernel);
        Objects.requireNonNull(address);
        Objects.requireNonNull(residence);
        if (kernel.region() != MemoryRegion.KERNEL || kernel.size() != 1 || address.address() != kernel.base()) {
            throw new IllegalArgumentException("PCB address must match its Kernel allocation");
        }
    }
    public ProcessResources withResidence(UserImageResidence residence) {
        return new ProcessResources(kernel, address, residence);
    }
}
