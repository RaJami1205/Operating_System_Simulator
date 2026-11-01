package io.github.rajami1205.osimulator.application.program;

import io.github.rajami1205.osimulator.model.memory.MainMemory;
import java.util.Objects;

public record PreparedResidentProgramLoad(ResidentProgramLoad loaded, MainMemory.UserReservation reservation) {
    public PreparedResidentProgramLoad {
        Objects.requireNonNull(loaded); Objects.requireNonNull(reservation);
        if (!loaded.image().equals(reservation.image())) throw new IllegalArgumentException("Prepared image differs from reservation");
    }
}
