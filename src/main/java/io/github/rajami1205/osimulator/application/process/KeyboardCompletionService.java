package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.cpu.NumericRegisterValue;
import io.github.rajami1205.osimulator.model.io.KeyboardDevice;
import io.github.rajami1205.osimulator.model.memory.MainMemory;
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
    private final PcbKernelSynchronizer synchronizer;
    private final KeyboardDevice keyboard;
    private final ProcessTable table;
    private final ProcessResourceRegistry resources;
    private final ReadyQueue ready;
    private final SuspendedReadyQueue suspended;
    // Direcciones lógicas estables ante relocation.
    private record PendingInput(ProcessControlBlock process, int logicalPc) { }
    private final LinkedHashMap<Integer, PendingInput> pending = new LinkedHashMap<>();

    /** Asocia dispositivo FIFO, PCBs y colas de la sesión; comienza sin solicitudes pendientes. */
    public KeyboardCompletionService(KeyboardDevice keyboard, ProcessTable table, ProcessResourceRegistry resources,
            ReadyQueue ready, SuspendedReadyQueue suspended, MainMemory memory) {
        this.synchronizer = new PcbKernelSynchronizer(memory, resources);
        this.keyboard = Objects.requireNonNull(keyboard);
        this.table = Objects.requireNonNull(table);
        this.resources = Objects.requireNonNull(resources);
        this.ready = Objects.requireNonNull(ready);
        this.suspended = Objects.requireNonNull(suspended);
    }

    /** Devuelve copia inmutable de solicitudes en orden FIFO para inspección. */
    public List<PendingKeyboardRequest> pending() {
        return pending.entrySet().stream().map(entry -> new PendingKeyboardRequest(entry.getKey(),
                entry.getValue().logicalPc())).toList();
    }
    /** Retira la solicitud del PID durante cleanup, sin consumir valores del dispositivo. */
    public void remove(int pid) { pending.remove(pid); }

    /** Registra el INT09 de un PCB canónico BLOCKED con PC ejecutable, rechazando duplicados. */
    public void register(ProcessControlBlock pcb) {
        Objects.requireNonNull(pcb, "pcb must not be null");
        if (table.find(pcb.processId()).orElse(null) != pcb || pcb.state() != ProcessState.BLOCKED
                || !pcb.hasExecutableProgramCounter()) {
            throw new IllegalStateException("Invalid keyboard block");
        }
        int logicalPc = pcb.programCounter();
        if (pending.putIfAbsent(pcb.processId(), new PendingInput(pcb, logicalPc)) != null) {
            throw new IllegalStateException("Duplicate keyboard request");
        }
    }

    /**
     * Entrega input FIFO al DX guardado y avanza el PC sin ticks; reencola READY/READY_SUSPENDED o
     * devuelve PID terminales para cleanup.
     */
    public List<Integer> drain() {
        var terminated = new ArrayList<Integer>();
        while (!pending.isEmpty() && keyboard.hasInput()) {
            var request = pending().getFirst();
            int pid = request.processId();
            var pcb = table.find(pid).orElseThrow(() -> new IllegalStateException("Missing keyboard target"));
            var residence = resources.find(pid).orElseThrow().residence();
            boolean swapped = pcb.state() == ProcessState.BLOCKED_SUSPENDED;
            if (pending.get(pid).process() != pcb || (!swapped && pcb.state() != ProcessState.BLOCKED)
                    || pcb.programCounter() != request.interruptLogicalPc()
                    || !pcb.hasExecutableProgramCounter() || ready.entries().contains(pid)
                    || suspended.entries().contains(pid)
                    || (swapped != (residence instanceof UserImageResidence.Suspended))) {
                throw new IllegalStateException("Inconsistent keyboard target/context/residence");
            }
            synchronizer.validate(pcb);
            int next = request.interruptLogicalPc() + 1;
            int input = keyboard.poll().orElseThrow();
            pcb.replaceCpuContext(pcb.cpuContext().withDx(new NumericRegisterValue(input)).withProgramCounter(next));
            pending.remove(pid);
            if (pcb.hasTerminalProgramCounter()) {
                pcb.changeState(ProcessState.TERMINATED);
                terminated.add(pid);
            } else if (swapped) {
                pcb.changeState(ProcessState.READY_SUSPENDED);
                suspended.enqueue(pid);
            } else {
                pcb.changeState(ProcessState.READY);
                ready.enqueue(pid);
            }
            synchronizer.sync(pcb);
        }
        return List.copyOf(terminated);
    }
}
