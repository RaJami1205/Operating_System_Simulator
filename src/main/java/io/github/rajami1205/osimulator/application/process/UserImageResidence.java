package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.memory.MemoryAllocation;
import io.github.rajami1205.osimulator.model.memory.MemoryRegion;
import io.github.rajami1205.osimulator.model.storage.StorageAllocation;
import java.util.Objects;

/** Exactly one committed image residence; handles are never reconstructed from bounds. */
public sealed interface UserImageResidence {
    record Resident(MemoryAllocation allocation) implements UserImageResidence {
        public Resident {
            Objects.requireNonNull(allocation);
            if (allocation.region() != MemoryRegion.USER) throw new IllegalArgumentException("USER allocation required");
        }
    }
    record Suspended(StorageAllocation allocation) implements UserImageResidence {
        public Suspended { Objects.requireNonNull(allocation); }
    }
}
