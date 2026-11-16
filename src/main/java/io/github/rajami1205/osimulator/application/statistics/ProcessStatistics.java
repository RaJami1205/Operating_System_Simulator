package io.github.rajami1205.osimulator.application.statistics;

import io.github.rajami1205.osimulator.model.cpu.CpuId;
import java.util.Objects;

/**
 * Estadísticas de un proceso terminado (nomenclatura de Stallings, tabla 9.5).
 *
 * @param serviceTime          Ts: Burst declarado (instrucciones parseadas)
 * @param start                primer dispatch
 * @param turnaround           Tr = Finish − Arrival
 * @param normalizedTurnaround Tr / Ts
 * @param response             Start − Arrival
 * @param waitingTime          Wait = Tr − instrucciones ejecutadas
 * @param executedInstructions instrucciones realmente ejecutadas (difiere de Ts sólo con saltos/ciclos)
 */
public record ProcessStatistics(int processId, CpuId cpu, long arrival, int serviceTime, long start, long finish,
        long turnaround, double normalizedTurnaround, long response, long waitingTime, long executedInstructions) {
    public ProcessStatistics {
        Objects.requireNonNull(cpu, "cpu must not be null");
        if (serviceTime <= 0) {
            throw new IllegalArgumentException("Service time (Ts) must be positive");
        }
    }
}
