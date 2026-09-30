package io.github.rajami1205.osimulator.application.process;

import java.util.Objects;

public sealed interface SwapResult {
    record Completed() implements SwapResult {}
    record Waiting(Reason reason) implements SwapResult {
        public Waiting { Objects.requireNonNull(reason); }
    }
    enum Reason { INSUFFICIENT_SWAP, INSUFFICIENT_USER_MEMORY }
}
