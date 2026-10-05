package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.process.*;
import io.github.rajami1205.osimulator.application.simulator.SimulatorSnapshot.*;
import io.github.rajami1205.osimulator.model.configuration.SimulatorConfiguration;
import io.github.rajami1205.osimulator.model.cpu.*;
import io.github.rajami1205.osimulator.model.instruction.Instruction;
import io.github.rajami1205.osimulator.model.job.Job;
import io.github.rajami1205.osimulator.model.memory.*;
import io.github.rajami1205.osimulator.model.process.*;
import io.github.rajami1205.osimulator.model.storage.*;
import java.util.*;

/**
 * Copia observaciones de sesión a un read model inmutable; no expone entidades mutables ni handles de
 * allocation a Presentation.
 */
final class SimulatorSnapshotMapper {
    /** Impide instanciar el mapper de observaciones de sesión. */
    private SimulatorSnapshotMapper() {}

    /**
     * Copia observaciones de sesión y genera listas inmutables para JavaFX, tolerando recursos ausentes
     * antes de Initialize.
     */
    static SimulatorSnapshot map(SimulatorState state, Optional<RuntimeStatus> status,
            SimulatorConfiguration configuration, CpuRegisters<Instruction> cpu, Optional<ProcessControlBlock> owner,
            MainMemory memory, SecondaryStorage storage, List<Job> jobs, ProcessTable table,
            ProcessResourceRegistry resources, List<Integer> ready, List<Integer> suspended,
            List<PendingKeyboardRequest> pending, List<CompletedProcessRecord> completed, List<Integer> screen, OptionalLong cpuTicks) {
        var cpuView = cpu == null ? Optional.<CpuSnapshot>empty() : Optional.of(context(cpu.snapshot()));
        var instruction = owner.isEmpty() || cpu == null ? Optional.<InstructionSnapshot>empty()
                : cpu.instructionRegister().map(SemanticFormatter::instructionSnapshot);
        var processes = new ArrayList<ProcessDetails>();
        if (table != null) {
            for (var pcb : table.entries()) {
                var resource = resources.find(pcb.processId());
                // Una invariant fallida del registry no debe impedir inspeccionar el estado ERROR.
                if (resource.isEmpty() && state == SimulatorState.ERROR) continue;
                processes.add(process(pcb, resource.orElseThrow()));
            }
        }
        Optional<ProcessSnapshot> active = Optional.empty();
        var program = new ArrayList<ProgramEntry>();
        if (owner.isPresent()) {
            var pcb = owner.orElseThrow();
            var resource = resources.find(pcb.processId());
            if (resource.isPresent() && resource.orElseThrow().residence() instanceof UserImageResidence.Resident resident) {
                int base = resident.allocation().base();
                active = Optional.of(new ProcessSnapshot(pcb.processId(), pcb.state().name(), base,
                        pcb.instructionCount(), base + pcb.instructionCount(), pcb.programCounter()));
                try {
                    var image = memory.readUserBlock(resident.allocation());
                    for (int i = 0; i < image.size(); i++) {
                        program.add(new ProgramEntry(base + i, SemanticFormatter.semanticText(image.get(i))));
                    }
                } catch (RuntimeException failure) {
                    if (state != SimulatorState.ERROR) throw failure;
                    // Conserva la inspección del CPU y las celdas físicas aunque la allocation activa sea inválida.
                }
            }
        }
        return new SimulatorSnapshot(state, cpuView, instruction, active, program, memoryRows(memory), status,
                owner.map(ProcessControlBlock::processId), Optional.ofNullable(configuration), jobs, processes,
                ready, suspended, pending, completed.stream().map(value -> new CompletedProcess(
                        value.processId(), context(value.finalContext()), value.accounting())).toList(),
                storageRows(storage), screen, cpuTicks);
    }

    /** Copia registros/flags y formatea IR sin retener CpuRegisters ni ejecutar instrucciones. */
    static CpuSnapshot context(CpuContext<Instruction> value) {
        return new CpuSnapshot(value.programCounter(), value.accumulator(), value.ax(), value.bx(), value.cx(),
                value.dxValue(), value.instructionRegister().map(SemanticFormatter::semanticText),
                value.ah(), value.alValue(), value.conditionFlags());
    }

    /** Construye detalle del PCB y residencia; omite Base física al estar suspended y copia stack/archivos. */
    private static ProcessDetails process(ProcessControlBlock pcb, ProcessResources resource) {
        Optional<Integer> base = resource.residence() instanceof UserImageResidence.Resident resident
                ? Optional.of(resident.allocation().base()) : Optional.empty();
        return new ProcessDetails(pcb.processId(), pcb.state(), pcb.programCounter(),
                base.isPresent() ? Residency.RESIDENT : Residency.SUSPENDED, base, pcb.instructionCount(),
                pcb.priority(), context(pcb.cpuContext()), pcb.stack().values(), pcb.openFiles().files().stream().sorted().toList(),
                resource.address().address(), pcb.nextPcbAddress().map(PcbAddress::address), pcb.accounting());
    }

    /** Describe instrucciones y PCBs de forma segura; EmptyContent se representa como ausencia de texto. */
    static Optional<String> memoryText(MemoryContent content) {
        return switch (content) {
            case EmptyContent ignored -> Optional.empty();
            case InstructionContent value -> Optional.of(SemanticFormatter.semanticText(value.instruction()));
            case PcbContent value -> Optional.of("PCB PID=" + value.pcb().processId());
        };
    }

    /** Enumera celdas físicas para la vista de memoria; sin sesión devuelve lista vacía. */
    private static List<MemoryEntry> memoryRows(MainMemory memory) {
        if (memory == null) return List.of();
        var rows = new ArrayList<MemoryEntry>(memory.size());
        for (int address = 0; address < memory.size(); address++) {
            rows.add(new MemoryEntry(address, memory.regionOf(address).name(), memoryText(memory.read(address))));
        }
        return rows;
    }

    /** Enumera las regiones físicas de SecondaryStorage con contenido descriptivo para Presentation. */
    private static List<StorageEntry> storageRows(SecondaryStorage storage) {
        if (storage == null) return List.of();
        var rows = new ArrayList<StorageEntry>(storage.size());
        for (int address = 0; address < storage.size(); address++) {
            rows.add(new StorageEntry(address, storage.regionOf(address), storageText(storage.read(address))));
        }
        return rows;
    }

    /** Formatea índices, instrucciones, caracteres y posiciones vacías sin exponer recursos mutables. */
    static String storageText(StorageContent content) {
        return switch (content) {
            case EmptyStorageContent ignored -> "—";
            case StoredInstructionContent value -> SemanticFormatter.semanticText(value.instruction());
            case FileIndexEntry value -> value.kind() + " name=\"" + value.name() + "\" start="
                    + value.startAddress() + " length=" + value.length();
            case UserFileContent value -> characterText(value.value());
        };
    }

    /** Escapa controles, comillas y unidades Unicode no imprimibles para una celda legible y segura. */
    static String characterText(char value) {
        String escaped = switch (value) {
            case '\n' -> "\\n";
            case '\t' -> "\\t";
            case '\r' -> "\\r";
            case '\\' -> "\\\\";
            case '\'' -> "\\'";
            default -> Character.isISOControl(value) || Character.isSurrogate(value)
                    || Character.isIdentifierIgnorable(value) || !Character.isDefined(value)
                    || Character.getType(value) == Character.LINE_SEPARATOR
                    || Character.getType(value) == Character.PARAGRAPH_SEPARATOR
                    ? String.format(Locale.ROOT, "\\u%04X", (int) value) : Character.toString(value);
        };
        return "'" + escaped + "'";
    }
}
