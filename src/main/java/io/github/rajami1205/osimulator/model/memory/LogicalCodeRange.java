package io.github.rajami1205.osimulator.model.memory;

import java.util.Objects;

/** Intervalo ejecutable lógico con base positiva y marcador terminal int no ejecutable. */
public record LogicalCodeRange(LogicalAddress base, int length) {
    public LogicalCodeRange {
        Objects.requireNonNull(base, "base must not be null");
        if (base.value() == 0 || length <= 0 || (long) base.value() + length > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid logical code range");
        }
    }

    /** Marcador terminal, excluido de las direcciones ejecutables. */
    public LogicalAddress endExclusive() {
        return new LogicalAddress((int) ((long) base.value() + length));
    }
}
