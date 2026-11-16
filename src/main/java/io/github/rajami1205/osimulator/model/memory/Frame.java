package io.github.rajami1205.osimulator.model.memory;

public record Frame(int id, int base, int capacity) {
    public Frame {
        if (id < 0 || base < 0 || capacity <= 0 || (long) base + capacity > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid frame bounds");
        }
    }
}
