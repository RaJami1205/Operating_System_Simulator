package io.github.rajami1205.osimulator.model.io;

import java.util.ArrayList;
import java.util.List;

/** Salida numérica acumulada de la sesión que recibe INT10 desde DX; Presentation sólo consulta su copia. */
public final class ScreenDevice {
    private final List<Integer> outputs = new ArrayList<>();
    /** Añade una salida numérica en orden de emisión, sin interactuar con controles JavaFX. */
    public void append(int value) { outputs.add(value); }
    /** Devuelve una copia inmutable de las salidas acumuladas en la sesión. */
    public List<Integer> outputs() { return List.copyOf(outputs); }
}
