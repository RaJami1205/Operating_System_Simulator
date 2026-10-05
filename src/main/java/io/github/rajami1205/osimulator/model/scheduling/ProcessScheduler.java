package io.github.rajami1205.osimulator.model.scheduling;

import java.util.Optional;

/** Selecciona un candidato sin consumir membresía READY ni realizar dispatch. */
public interface ProcessScheduler {
    /**
     * Devuelve un candidato READY opcional sin consumir la cola, ejecutar instrucciones ni apropiarse del
     * CPU.
     */
    Optional<Integer> selectNext();
}
