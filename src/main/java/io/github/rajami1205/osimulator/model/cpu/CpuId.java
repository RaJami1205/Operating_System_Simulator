package io.github.rajami1205.osimulator.model.cpu;

/** Identidad estable; la topología valida además pertenencia al rango configurado. */
public record CpuId(int value) {
    public static final CpuId ZERO = new CpuId(0);
    public static final int MAX_CPU_COUNT = 5;

    public CpuId {
        if (value < 0 || value >= MAX_CPU_COUNT) throw new IllegalArgumentException("CPU ID must be between 0 and 4");
    }
}
