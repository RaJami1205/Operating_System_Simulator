package io.github.rajami1205.osimulator.model.process;

import io.github.rajami1205.osimulator.model.cpu.CpuValueRange;
import io.github.rajami1205.osimulator.model.process.exception.ProcessStackOverflowException;
import io.github.rajami1205.osimulator.model.process.exception.ProcessStackUnderflowException;
import java.util.ArrayList;
import java.util.List;

/**
 * Stack numérico del proceso con capacidad cinco; sobrevive a context switch y swap porque pertenece al
 * PCB.
 */
public final class ProcessStack {
    public static final int CAPACITY = 5;
    private final List<Integer> values = new ArrayList<>(CAPACITY);

    /**
     * Valida signed 16-bit y capacidad antes de añadir el valor al tope; falla con overflow si ya hay
     * cinco.
     */
    public void push(int value) {
        CpuValueRange.validateRegisterValue(value);
        if (isFull()) {
            throw new ProcessStackOverflowException();
        }
        values.add(value);
    }

    /**
     * Valida todo el lote y su capacidad antes de publicar cambios; conserva el orden recibido para que
     * PARAM pueda invertirlo previamente.
     */
    public void pushAll(List<Integer> values) {
        List<Integer> copy = List.copyOf(values);
        copy.forEach(CpuValueRange::validateRegisterValue);
        if (copy.size() > CAPACITY - size()) {
            throw new ProcessStackOverflowException();
        }
        this.values.addAll(copy);
    }

    /** Retira y devuelve el último valor; lanza underflow sin mutación si está vacía. */
    public int pop() {
        if (isEmpty()) {
            throw new ProcessStackUnderflowException();
        }
        return values.removeLast();
    }

    /** Cuenta los valores actualmente apilados por el proceso. */
    public int size() { return values.size(); }
    /** Indica si un POP carecería de valor disponible. */
    public boolean isEmpty() { return values.isEmpty(); }
    /** Indica si se alcanzó la capacidad fija de cinco valores. */
    public boolean isFull() { return size() == CAPACITY; }

    /** Copia inmutable ordenada desde el fondo hasta el tope. */
    public List<Integer> values() { return List.copyOf(values); }
}
