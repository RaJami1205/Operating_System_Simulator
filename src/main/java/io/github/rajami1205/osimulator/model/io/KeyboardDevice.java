package io.github.rajami1205.osimulator.model.io;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalInt;

/** FIFO de sesión con valores 0..255; no conserva estado de procesos ni bloquea el hilo de JavaFX. */
public final class KeyboardDevice {
    public static final int MIN_VALUE = 0;
    public static final int MAX_VALUE = 255;
    private final Deque<Integer> values = new ArrayDeque<>();

    /** Valida 0..255 y encola al final; el runtime asigna valores a solicitudes FIFO, no a PID específicos. */
    public void submit(int value) {
        if (value < MIN_VALUE || value > MAX_VALUE) {
            throw new IllegalArgumentException("Keyboard input must be between 0 and 255: " + value);
        }
        values.addLast(value);
    }

    /** Indica si existe al menos un valor disponible sin consumirlo. */
    public boolean hasInput() { return !values.isEmpty(); }
    /** Consume el valor más antiguo o devuelve vacío, sin bloquear el hilo llamador. */
    public OptionalInt poll() {
        Integer value = values.pollFirst();
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }
}
