package io.github.rajami1205.osimulator.application.simulator;

/**
 * Disponibilidad global: RUNNABLE permite ticks; WAITING_FOR_CAPACITY espera recursos;
 * FINISHED indica workload agotado, no un único PCB terminado.
 */
public enum RuntimeStatus { RUNNABLE, WAITING_FOR_CAPACITY, FINISHED }
