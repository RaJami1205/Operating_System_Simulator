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
                .map(node -> cpuView(node, memory, storage, resources)).toList();
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
                simulationTimeSeconds, cpus, lastCpuResults, partitionViews(memory, resources), pagingView(memory, resources));
    }

    private static PagingView pagingView(MainMemory memory, ProcessResourceRegistry resources) {
        if (memory == null) return new PagingView(List.of(), List.of(), 0);
        var owners = new HashMap<FrameHandle, Integer>();
        var pages = new HashMap<FrameHandle, Integer>();
        var tables = new ArrayList<PageTableView>();
        var pool = memory.frames();
        var liveHandles = pool.stream().flatMap(frame -> frame.handle().stream()).collect(java.util.stream.Collectors.toSet());
        if (resources != null) resources.entries().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            var residence = entry.getValue().residence();
            PageTable table = switch (residence) {
                case UserImageResidence.Paged paged -> paged.table();
                case UserImageResidence.Demand demand -> demand.table();
                case UserImageResidence.DemandSuspended demand -> demand.table();
                default -> null;
            };
            if (table != null) {
                // Una sesión en ERROR tampoco presenta mappings liberados como residencia vigente.
                if (!liveHandles.containsAll(table.frames())) return;
                var mappings = new ArrayList<PageMapping>();
                for (var page : table.entries()) {
                    page.mapping().ifPresent(handle -> { owners.put(handle, entry.getKey()); pages.put(handle, page.pageNumber()); });
                    mappings.add(new PageMapping(page.pageNumber(), page.mapping().map(h -> h.frame().id()), page.mapping().map(h -> h.frame().base()),
                            Math.min(table.pageSize(), table.size() - page.pageNumber() * table.pageSize())));
                }
                var backing = switch (residence) {
                    case UserImageResidence.Demand demand -> Optional.of(new BackingView(demand.backing().allocation().base(), demand.backing().programLength()));
                    case UserImageResidence.DemandSuspended demand -> Optional.of(new BackingView(demand.backing().allocation().base(), demand.backing().programLength()));
                    default -> Optional.<BackingView>empty();
                };
                tables.add(new PageTableView(entry.getKey(), table.size(), table.pageSize(), mappings,
                        table.loadMode(), table.revision(), backing, residence.suspended()));
            }
        });
        var frames = pool.stream().map(state -> new FrameView(state.frame().id(), state.frame().base(),
                state.frame().capacity(), state.handle().isPresent(), state.handle().map(owners::get), state.handle().map(pages::get),
                memory.clockView().flatMap(clock -> state.handle().map(clock.references()::get)))).toList();
        return new PagingView(frames, tables, memory.unassignableUserCells(), resources == null ? List.of() : resources.pageEvents(),
                resources == null ? List.of() : resources.pendingPages().stream().map(r -> new PendingPageView(r.processId(), r.pageNumber(), r.logicalPc())).toList(),
                memory.clockView().map(ClockReplacement.View::hand));
    }

    private static List<PartitionView> partitionViews(MainMemory memory, ProcessResourceRegistry resources) {
        if (memory == null) return List.of();
        var owners = new HashMap<MemoryAllocation, Integer>();
        if (resources != null) resources.entries().forEach((pid, resource) -> {
            if (resource.residence() instanceof UserImageResidence.Resident resident) {
                owners.put(resident.allocation(), pid);
            }
        });
        return memory.partitions().stream().map(state -> {
            var partition = state.partition();
            return new PartitionView(partition.id(), partition.base(), partition.capacity(), state.occupied(),
                    state.allocation().map(owners::get), state.usedLength(), state.internalFragmentation());
        }).toList();
    }

    private static CpuView cpuView(CpuNode node,
            MainMemory memory, SecondaryStorage storage, ProcessResourceRegistry resources) {
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
            if (resource.isPresent() && !resource.orElseThrow().residence().suspended()) {
                var resident = resource.orElseThrow().residence().residentImage();
                if (resident instanceof MemoryAllocation allocation) {
                    active = Optional.of(new ProcessSnapshot(pcb.processId(), pcb.state().name(), allocation.base(),
                            pcb.instructionCount(), allocation.endExclusive(), pcb.programCounter()));
                }
                try {
                    if (pcb.state() != ProcessState.TERMINATED && pcb.hasExecutableProgramCounter()) {
                        memory.validateImage(resident, pcb);
                        var resolution = memory.resolveInstructionAddress(resident, pcb.logicalCodeRange(), new LogicalAddress(cpu.programCounter()));
                        if (resolution instanceof AddressResolution.Present present) nextPhysical = Optional.of(present.address().value());
                    }
                    var image = resource.orElseThrow().residence() instanceof UserImageResidence.Demand demand
                            ? storage.readSwapBlock(demand.backing().allocation()) : memory.readImage(resident);
                    for (int i = 0; i < image.size(); i++) {
                        int logical = pcb.logicalInstructionAddress(i);
                        var resolution = memory.resolveInstructionAddress(resident, pcb.logicalCodeRange(), new LogicalAddress(logical));
                        var physical = resolution instanceof AddressResolution.Present present ? Optional.of(present.address().value()) : Optional.<Integer>empty();
                        program.add(new ProgramEntry(physical, SemanticFormatter.semanticText(image.get(i)),
                                resident instanceof PageTable ? Optional.of(logical) : Optional.empty()));
                    }
                    program.sort(Comparator.comparingInt((ProgramEntry p) -> p.physicalAddress().orElse(Integer.MAX_VALUE))
                            .thenComparingInt(p -> p.logicalAddress().orElse(0)));
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
                resource.residence().suspended() ? Residency.SUSPENDED : Residency.RESIDENT, base, pcb.instructionCount(),
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
            var content = address >= memory.size() - memory.unassignableUserCells()
                    ? Optional.of("Unassignable USER remainder") : memoryText(memory.read(address));
            rows.add(new MemoryEntry(address, memory.regionOf(address).name(), content));
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
