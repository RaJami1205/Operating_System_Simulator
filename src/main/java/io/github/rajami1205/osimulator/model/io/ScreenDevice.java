package io.github.rajami1205.osimulator.model.io;

import java.util.ArrayList;
import java.util.List;

/** Session-owned numeric output, independent of presentation. */
public final class ScreenDevice {
    private final List<Integer> outputs = new ArrayList<>();
    public void append(int value) { outputs.add(value); }
    public List<Integer> outputs() { return List.copyOf(outputs); }
}
