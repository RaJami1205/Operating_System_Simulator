package io.github.rajami1205.osimulator.application.simulator;

import io.github.rajami1205.osimulator.application.lifecycle.SimulatorState;
import io.github.rajami1205.osimulator.application.cpu.CpuNode;
import io.github.rajami1205.osimulator.application.cpu.CpuTopology;
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
            SimulatorConfiguration configuration, CpuTopology topology,
            MainMemory memory, SecondaryStorage storage, List<Job> jobs, ProcessTable table,
            ProcessResourceRegistry resources, List<CompletedProcessRecord> completed, List<Integer> screen,
            OptionalLong simulationTimeSeconds, List<RuntimeStepResult.CpuResult> lastCpuResults) {
        var cpus = topology == null ? List.<CpuView>of() : topology.nodes().stream()
                .map(node -> cpuView(node, memory, resources)).toList();
        var zero = cpus.isEmpty() ? Optional.<CpuView>empty() : Optional.of(cpus.getFirst());
        var translationError = cpus.stream().flatMap(value -> value.addressTranslationError().stream()).findFirst();
        if (translationError.isPresent()) { state = SimulatorState.ERROR; status = Optional.empty(); }
        var processes = new ArrayList<ProcessDetails>();
        if (table != null) {
            for (var pcb : table.entries()) {
                var resource = resources.find(pcb.processId());
                if (resource.isEmpty() && state == SimulatorState.ERROR) continue;
                processes.add(process(pcb, resource.orElseThrow()));
            }
        }
        return new SimulatorSnapshot(state, zero.map(CpuView::cpu), zero.flatMap(CpuView::currentInstruction),
                zero.flatMap(CpuView::process), zero.map(CpuView::program).orElse(List.of()), memoryRows(memory), status,
                zero.flatMap(CpuView::ownerPid), Optional.ofNullable(configuration), jobs, processes,
                zero.map(CpuView::readyQueue).orElse(List.of()), zero.map(CpuView::suspendedReadyQueue).orElse(List.of()),
                completed.stream().map(value -> new CompletedProcess(value.processId(), context(value.finalContext()),
                        value.accounting(), value.logicalTimes(), value.declaredBurst(), value.cpuAffinity())).toList(),
                storageRows(storage), screen, zero.isEmpty() ? OptionalLong.empty() : OptionalLong.of(zero.orElseThrow().cpuTicks()),
                zero.flatMap(CpuView::nextInstructionPhysicalAddress), zero.flatMap(CpuView::addressTranslationError),
                simulationTimeSeconds, cpus, lastCpuResults);
    }

    private static CpuView cpuView(CpuNode node,
            MainMemory memory, ProcessResourceRegistry resources) {
        var cpu = node.registers();
        var owner = node.dispatcher().owner();
        var instruction = owner.isEmpty() ? Optional.<InstructionSnapshot>empty()
                : cpu.instructionRegister().map(SemanticFormatter::instructionSnapshot);
        Optional<ProcessSnapshot> active = Optional.empty();
        Optional<Integer> nextPhysical = Optional.empty();
        Optional<String> translationError = Optional.empty();
        var program = new ArrayList<ProgramEntry>();
        if (owner.isPresent()) {
            var pcb = owner.orElseThrow();
            var resource = resources.find(pcb.processId());
            if (resource.isPresent() && resource.orElseThrow().residence() instanceof UserImageResidence.Resident resident) {
                int base = resident.allocation().base();
                active = Optional.of(new ProcessSnapshot(pcb.processId(), pcb.state().name(), base,
                        pcb.instructionCount(), base + pcb.instructionCount(), pcb.programCounter()));
                try {
                    if (pcb.state() != ProcessState.TERMINATED && pcb.hasExecutableProgramCounter()) {
                        memory.validateUserAllocation(resident.allocation(), pcb.memoryBounds());
                        nextPhysical = Optional.of(memory.translateInstructionAddress(resident.allocation(),
                                pcb.logicalCodeRange(), new LogicalAddress(cpu.programCounter())).value());
                    }
                    var image = memory.readUserBlock(resident.allocation());
                    for (int i = 0; i < image.size(); i++) {
                        program.add(new ProgramEntry(base + i, SemanticFormatter.semanticText(image.get(i))));
                    }
                } catch (RuntimeException failure) {
                    nextPhysical = Optional.empty();
                    translationError = Optional.of("Invalid instruction allocation/address");
                    // Conserva la inspección del CPU y las celdas físicas aunque la allocation activa sea inválida.
                }
            }
        }

        return new CpuView(node.id(), context(cpu.snapshot()), owner.map(ProcessControlBlock::processId),
                instruction, active, program, node.tickCounter().current(), node.readyQueue().entries(),
                node.suspendedReadyQueue().entries(), nextPhysical, translationError);
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
                resource.address().address(), pcb.nextPcbAddress().map(PcbAddress::address), pcb.accounting(), pcb.logicalTimes(), pcb.cpuAffinity());
    }

    /** Describe instrucciones y PCBs de forma segura; EmptyContent se representa como ausencia de texto. */
    static Optional<String> memoryText(MemoryContent content) {
        return switch (content) {
            case EmptyContent ignored -> Optional.empty();
            case InstructionContent value -> Optional.of(SemanticFormatter.semanticText(value.instruction()));
            case PcbContent value -> Optional.of("PCB " + value.ownerPid() + " · " + value.field().name() + ": " + pcbValueText(value.value()));
        };
    }

    /** Formatea valores capturados sin consultar un PCB vivo. */
    private static String pcbValueText(PcbValue value) {
        return switch (value) {
            case PcbValue.Empty ignored -> "—";
            case PcbValue.Number number -> Long.toString(number.value());
            case PcbValue.Flag flag -> Boolean.toString(flag.value());
            case PcbValue.State state -> state.value().name();
            case PcbValue.InstructionValue instruction -> SemanticFormatter.semanticText(instruction.value());
            case PcbValue.Register register -> register.value().displayText();
            case PcbValue.Timestamp time -> time.value().toString();
            case PcbValue.Names names -> names.values().toString();
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
