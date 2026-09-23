package io.github.rajami1205.osimulator.model.process;

import io.github.rajami1205.osimulator.model.cpu.CpuValueRange;
import io.github.rajami1205.osimulator.model.process.exception.ProcessStackOverflowException;
import io.github.rajami1205.osimulator.model.process.exception.ProcessStackUnderflowException;
import java.util.ArrayList;
import java.util.List;

/** Pila numérica propia del proceso, independiente de las instrucciones futuras. */
public final class ProcessStack {
    public static final int CAPACITY = 5;
    private final List<Integer> values = new ArrayList<>(CAPACITY);

    public void push(int value) {
        CpuValueRange.validateRegisterValue(value);
        if (isFull()) {
            throw new ProcessStackOverflowException();
        }
        values.add(value);
    }

    public int pop() {
        if (isEmpty()) {
            throw new ProcessStackUnderflowException();
        }
        return values.removeLast();
    }

    public int size() { return values.size(); }
    public boolean isEmpty() { return values.isEmpty(); }
    public boolean isFull() { return size() == CAPACITY; }

    /** Copia inmutable ordenada desde el fondo hasta el tope. */
    public List<Integer> values() { return List.copyOf(values); }
}
