package io.github.rajami1205.osimulator.application.program;

import io.github.rajami1205.osimulator.model.memory.MainMemory;
import java.util.Objects;

/** Carga preparada; el receptor debe confirmar o cancelar su reserva USER en la misma operación. */
public record PreparedProgramLoad(ProgramLoadResult loaded, MainMemory.UserReservation reservation) {
    public PreparedProgramLoad {
        Objects.requireNonNull(loaded);
        Objects.requireNonNull(reservation);
        if (!loaded.userAllocation().equals(reservation.allocation())) {
            throw new IllegalArgumentException("Reservation does not match loaded USER image");
        }
    }
}
