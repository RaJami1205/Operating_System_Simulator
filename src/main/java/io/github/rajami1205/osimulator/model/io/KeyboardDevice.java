package io.github.rajami1205.osimulator.model.io;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalInt;

/** Session-owned FIFO of validated keyboard values; contains no process state. */
public final class KeyboardDevice {
    public static final int MIN_VALUE = 0;
    public static final int MAX_VALUE = 255;
    private final Deque<Integer> values = new ArrayDeque<>();

    public void submit(int value) {
        if (value < MIN_VALUE || value > MAX_VALUE) {
            throw new IllegalArgumentException("Keyboard input must be between 0 and 255: " + value);
        }
        values.addLast(value);
    }

    public boolean hasInput() { return !values.isEmpty(); }
    public OptionalInt poll() {
        Integer value = values.pollFirst();
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }
}
