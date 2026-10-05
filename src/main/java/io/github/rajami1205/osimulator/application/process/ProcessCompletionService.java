package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.memory.MainMemory;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.storage.SecondaryStorage;
import io.github.rajami1205.osimulator.model.scheduling.*;
import java.util.ArrayList;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Centraliza cleanup e historial de procesos terminados. Prevalida recursos y enlaces antes de registrar
 * finish con Clock y liberar recursos.
 */
public final class ProcessCompletionService {
    private final MainMemory memory;
    private final Clock realClock;
    private final SecondaryStorage storage;
    private final ProcessTable table;
    private final ProcessResourceRegistry resources;
    private final ReadyQueue ready;
    private final SuspendedReadyQueue suspended;
    private final KeyboardCompletionService keyboard;
    private final Dispatcher dispatcher;
    private final List<CompletedProcessRecord> completed = new ArrayList<>();

    /** Recibe recursos, Dispatcher y Clock de la sesión para validar cleanup y registrar timestamps reales. */
    public ProcessCompletionService(MainMemory memory, SecondaryStorage storage, ProcessTable table,
            ProcessResourceRegistry resources, ReadyQueue ready, SuspendedReadyQueue suspended,
            KeyboardCompletionService keyboard, Dispatcher dispatcher, Clock realClock) {
        this.realClock = Objects.requireNonNull(realClock);
        this.memory = Objects.requireNonNull(memory);
        this.storage = Objects.requireNonNull(storage);
        this.table = Objects.requireNonNull(table);
        this.resources = Objects.requireNonNull(resources);
        this.ready = Objects.requireNonNull(ready);
        this.suspended = Objects.requireNonNull(suspended);
        this.keyboard = Objects.requireNonNull(keyboard);
        this.dispatcher = Objects.requireNonNull(dispatcher);
    }

    /** Devuelve copia inmutable del historial en orden de completion, sin conservar PCBs vivos. */
    public List<CompletedProcessRecord> completed() { return List.copyOf(completed); }

    /**
     * Prevalida handles y cadena PCB, captura finish con Clock y crea historial antes del cleanup. Libera
     * USER o VIRTUAL_MEMORY y Kernel sin consumir ticks.
     */
    public void complete(int pid) {
        var pcb = table.find(pid).orElseThrow(() -> new IllegalStateException("Missing terminated PCB"));
        if (pcb.state() != ProcessState.TERMINATED || dispatcher.owner().orElse(null) == pcb
                || ready.entries().contains(pid)) {
            throw new IllegalStateException("Process must terminate and leave CPU/READY before cleanup");
        }
        var resource = resources.find(pid).orElseThrow(() -> new IllegalStateException("Missing completed resources"));
        memory.validatePcbAllocation(resource.kernel(), pcb);
        int size = switch (resource.residence()) {
            case UserImageResidence.Resident resident -> {
                if (resident.allocation().base() != pcb.memoryBounds().base()) {
                    throw new IllegalStateException("Invalid resident bounds");
                }
                yield memory.readUserBlock(resident.allocation()).size();
            }
            case UserImageResidence.Suspended swapped -> storage.readSwapBlock(swapped.allocation()).size();
        };
        if (size != pcb.instructionCount()) throw new IllegalStateException("Completed image length mismatch");

        var entries = table.entries();
        int index = entries.indexOf(pcb);
        ProcessControlBlock previous = index == 0 ? null : entries.get(index - 1);
        Optional<PcbAddress> next = index + 1 == entries.size() ? Optional.empty() : Optional.of(
                resources.find(entries.get(index + 1).processId()).orElseThrow().address());
        if (!pcb.nextPcbAddress().equals(next)) throw new IllegalStateException("Invalid outgoing PCB link");
        if (previous != null) {
            memory.validatePcbAllocation(resources.find(previous.processId()).orElseThrow().kernel(), previous);
            if (!previous.nextPcbAddress().equals(Optional.of(resource.address()))) {
                throw new IllegalStateException("Invalid incoming PCB link");
            }
        }

        pcb.replaceAccounting(pcb.accounting().finishAt(realClock.instant()));
        var record = new CompletedProcessRecord(pid, pcb.cpuContext(), pcb.accounting());
        // Todas las comprobaciones de identidad, contenido y enlaces preceden a las operaciones destructivas.
        switch (resource.residence()) {
            case UserImageResidence.Resident resident -> memory.release(resident.allocation());
            case UserImageResidence.Suspended swapped -> storage.releaseSwap(swapped.allocation());
        }
        if (previous != null) previous.setNextPcbAddress(next);
        memory.release(resource.kernel());
        resources.remove(pid);
        table.remove(pid);
        keyboard.remove(pid);
        suspended.remove(pid);
        completed.add(record);
    }
}
