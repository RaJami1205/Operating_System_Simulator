package io.github.rajami1205.osimulator.model.memory;

import java.util.Objects;
import java.util.UUID;

/** Ausencia válida es distinta de corrupción de direcciones o identidad. */
public sealed interface AddressResolution {
    record Present(PhysicalAddress address) implements AddressResolution {
        public Present { Objects.requireNonNull(address); }
    }
    record PageNotPresent(UUID imageId, int processId, long revision, int pageNumber) implements AddressResolution {
        public PageNotPresent {
            Objects.requireNonNull(imageId);
            if (processId <= 0 || revision < 0 || pageNumber < 0) throw new IllegalArgumentException("Invalid page fault");
        }
    }
}
