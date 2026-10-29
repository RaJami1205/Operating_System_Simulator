package io.github.rajami1205.osimulator.application.simulator;

/**
 * Disponibilidad global: RUNNABLE permite CPU ticks; WAITING_FOR_CAPACITY espera recursos;
 * WAITING_FOR_ARRIVAL sólo conserva Jobs futuros;
 * FINISHED indica workload agotado, no un único PCB terminado.
 */
public enum RuntimeStatus { RUNNABLE, WAITING_FOR_CAPACITY, WAITING_FOR_ARRIVAL, FINISHED }
