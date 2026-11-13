package io.github.rajami1205.osimulator.model.configuration;

/**
 * Parámetros de la política Priority para toda la sesión.
 *
 * @param preemptive    true: un READY con prioridad estrictamente mejor expulsa al owner al inicio del tick
 * @param agingInterval N: la prioridad efectiva mejora en 1 cada N ticks esperando en READY (0 = sin aging)
 */
public record PriorityConfiguration(boolean preemptive, int agingInterval) {
    public static final int MIN_AGING_INTERVAL = 0;
    public static final int MAX_AGING_INTERVAL = 50;

    public PriorityConfiguration {
        if (agingInterval < MIN_AGING_INTERVAL || agingInterval > MAX_AGING_INTERVAL) {
            throw new IllegalArgumentException("Aging interval must be between "
                    + MIN_AGING_INTERVAL + " and " + MAX_AGING_INTERVAL);
        }
    }

    /** No apropiativo y sin aging, para que los ejercicios clásicos coincidan. */
    public static PriorityConfiguration defaults() {
        return new PriorityConfiguration(false, 0);
    }
}
