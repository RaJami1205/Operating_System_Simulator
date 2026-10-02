package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.cpu.NumericRegisterValue;
import io.github.rajami1205.osimulator.model.io.KeyboardDevice;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.scheduling.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Completa solicitudes INT09 en FIFO sobre el contexto guardado del PCB, nunca sobre el CPU activo de otro
 * proceso. La completion no consume ticks.
 */
public final class KeyboardCompletionService {
    private final KeyboardDevice keyboard;
    private final ProcessTable table;
    private final ProcessResourceRegistry resources;
    private final ReadyQueue ready;
    private final SuspendedReadyQueue suspended;
    private final LinkedHashMap<Integer, PendingKeyboardRequest> pending = new LinkedHashMap<>();

    /** Asocia dispositivo FIFO, PCBs y colas de la sesión; comienza sin solicitudes pendientes. */
    public KeyboardCompletionService(KeyboardDevice keyboard, ProcessTable table, ProcessResourceRegistry resources,
            ReadyQueue ready, SuspendedReadyQueue suspended) {
        this.keyboard = Objects.requireNonNull(keyboard);
        this.table = Objects.requireNonNull(table);
        this.resources = Objects.requireNonNull(resources);
        this.ready = Objects.requireNonNull(ready);
        this.suspended = Objects.requireNonNull(suspended);
    }

    /** Devuelve copia inmutable de solicitudes en orden FIFO para inspección. */
    public List<PendingKeyboardRequest> pending() { return List.copyOf(pending.values()); }
    /** Retira la solicitud del PID durante cleanup, sin consumir valores del dispositivo. */
    public void remove(int pid) { pending.remove(pid); }

    /** Registra el INT09 de un PCB canónico BLOCKED con PC ejecutable, rechazando duplicados. */
    public void register(ProcessControlBlock pcb) {
        Objects.requireNonNull(pcb, "pcb must not be null");
        if (table.find(pcb.processId()).orElse(null) != pcb || pcb.state() != ProcessState.BLOCKED
                || pcb.programCounter() >= pcb.instructionCount()) {
            throw new IllegalStateException("Invalid keyboard block");
        }
        var request = new PendingKeyboardRequest(pcb.processId(), pcb.programCounter());
        if (pending.putIfAbsent(pcb.processId(), request) != null) throw new IllegalStateException("Duplicate keyboard request");
    }

    /**
     * Entrega input FIFO al DX guardado y avanza el PC sin ticks; reencola READY/READY_SUSPENDED o
     * devuelve PID terminales para cleanup.
     */
    public List<Integer> drain() {
        var terminated = new ArrayList<Integer>();
        while (!pending.isEmpty() && keyboard.hasInput()) {
            var request = pending.firstEntry().getValue();
            int pid = request.processId();
            var pcb = table.find(pid).orElseThrow(() -> new IllegalStateException("Missing keyboard target"));
            var residence = resources.find(pid).orElseThrow().residence();
            boolean swapped = pcb.state() == ProcessState.BLOCKED_SUSPENDED;
            if ((!swapped && pcb.state() != ProcessState.BLOCKED) || pcb.programCounter() != request.interruptPc()
                    || pcb.programCounter() >= pcb.instructionCount() || ready.entries().contains(pid)
                    || suspended.entries().contains(pid)
                    || (swapped != (residence instanceof UserImageResidence.Suspended))) {
                throw new IllegalStateException("Inconsistent keyboard target/context/residence");
            }
            int next = request.interruptPc() + 1;
            int input = keyboard.poll().orElseThrow();
            pcb.replaceCpuContext(pcb.cpuContext().withDx(new NumericRegisterValue(input)).withProgramCounter(next));
            pending.remove(pid);
            if (next == pcb.instructionCount()) {
                pcb.changeState(ProcessState.TERMINATED);
                terminated.add(pid);
            } else if (swapped) {
                pcb.changeState(ProcessState.READY_SUSPENDED);
                suspended.enqueue(pid);
            } else {
                pcb.changeState(ProcessState.READY);
                ready.enqueue(pid);
            }
        }
        return List.copyOf(terminated);
    }
}
