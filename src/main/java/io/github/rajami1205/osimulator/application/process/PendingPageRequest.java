package io.github.rajami1205.osimulator.application.process;

import java.util.Objects;
import java.util.UUID;

public record PendingPageRequest(int processId, UUID imageId, int pageNumber, int logicalPc) {
    public PendingPageRequest {
        Objects.requireNonNull(imageId);
        if (processId <= 0 || pageNumber < 0 || logicalPc < 4096) throw new IllegalArgumentException("Invalid pending page request");
    }
}
