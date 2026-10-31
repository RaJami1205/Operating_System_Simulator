package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.process.ProcessStack;

/**
 * Única autoridad de offsets del bloque PCB; el orden forma parte del contrato físico.
 * PC es lógico, BASE y NEXT_PCB son direcciones físicas, LIMIT es longitud del programa.
 * CPU_ID es afinidad fija desde admission; START/FINISH siguen siendo timestamps reales.
 */
public enum PcbField {
    PID, STATE, PC, IR, AC, AX, BX, CX, DX, AH, AL, EQUAL, OVERFLOW,
    BASE, LIMIT, PRIORITY, CPU_ID, START, CPU_TICKS, FINISH, OPEN_FILES,
    STACK_TOP, STACK_0, STACK_1, STACK_2, STACK_3, STACK_4, NEXT_PCB;

    public static final int PCB_SIZE = 28;
    public static PcbField stackSlot(int index) {
        if (index < 0 || index >= ProcessStack.CAPACITY) {
            throw new IllegalArgumentException("Invalid PCB stack slot");
        }
        return values()[STACK_0.offset() + index];
    }
    public int offset() { return ordinal(); }
}
