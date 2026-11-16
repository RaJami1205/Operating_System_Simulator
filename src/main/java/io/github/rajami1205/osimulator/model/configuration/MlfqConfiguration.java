package io.github.rajami1205.osimulator.model.configuration;

import java.util.List;
import java.util.Objects;

/** Quanta en orden de prioridad, empezando por el nivel cero. */
public record MlfqConfiguration(List<Integer> levelQuanta) {
    private static final MlfqConfiguration DEFAULT = new MlfqConfiguration(List.of(1, 2, 4));

    public MlfqConfiguration {
        Objects.requireNonNull(levelQuanta, "MLFQ quanta must not be null");
        levelQuanta = levelQuanta.stream()
                .map(q -> Objects.requireNonNull(q, "MLFQ level quantum must not be null")).toList();
        if (levelQuanta.size() < 2 || levelQuanta.size() > 5)
            throw new IllegalArgumentException("MLFQ requires 2 to 5 levels.");
        for (int quantum : levelQuanta) {
            if (quantum < 1 || quantum > 10)
                throw new IllegalArgumentException("MLFQ quantum must be between 1 and 10 at every level.");
        }
    }

    public static MlfqConfiguration defaults() { return DEFAULT; }
}
