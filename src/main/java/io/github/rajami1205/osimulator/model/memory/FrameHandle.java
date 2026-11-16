package io.github.rajami1205.osimulator.model.memory;

import java.util.Objects;
import java.util.UUID;

/** Identidad de una ocupación, distinta del índice reutilizable del frame. */
public record FrameHandle(UUID allocationId, Frame frame) {
    public FrameHandle { Objects.requireNonNull(allocationId); Objects.requireNonNull(frame); }
}
