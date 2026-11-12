package io.github.rajami1205.osimulator.model.scheduling;

import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import java.util.Optional;

/** Selecciona un candidato sin consumir membresía READY ni realizar dispatch. */
public interface ProcessScheduler {
    /**
     * Devuelve un candidato READY opcional sin consumir la cola, ejecutar instrucciones ni apropiarse del
     * CPU.
     */
    Optional<Integer> selectNext();

    /**
     * Indica si el owner actual debe ser expulsado antes del próximo dispatch.
     * El default es false; las políticas preemptivas sobreescriben este método.
     */
    default boolean shouldPreempt(ProcessControlBlock owner) {
        return false;
    }

    /**
     * Informa el tiempo lógico vigente justo antes de que esta CPU elija candidato. Default: sin efecto;
     * las políticas que dependen del tiempo (p. ej. HRRN) lo guardan para evaluar selectNext().
     */
    default void onSchedulingPoint(long now) { }

    /** Notifica que el proceso fue despachado a esta CPU. Default: sin efecto. */
    default void onDispatched(int pid) { }

    /** Notifica que el owner completó una instrucción. Default: sin efecto. */
    default void onInstructionCompleted(int pid) { }
}
