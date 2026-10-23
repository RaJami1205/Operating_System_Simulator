package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import io.github.rajami1205.osimulator.model.process.ProcessState;
import io.github.rajami1205.osimulator.model.process.PcbAddress;
import io.github.rajami1205.osimulator.model.process.ProcessStack;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.time.Instant;
import static io.github.rajami1205.osimulator.model.memory.PcbField.*;

/** Imagen completa preparada antes de escribir; no expone estado mutable del PCB. */
public final class PcbMemoryImage {
    private final List<PcbContent> cells;

    private PcbMemoryImage(ProcessControlBlock pcb, PcbValue[] values) {
        if (values.length != PCB_SIZE || Arrays.stream(values).anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Incomplete PCB image");
        }
        cells = Arrays.stream(PcbField.values())
                .map(field -> new PcbContent(pcb, field, values[field.offset()])).toList();
    }
    public List<PcbContent> cells() { return cells; }

    public static PcbMemoryImage capture(ProcessControlBlock pcb) {
        return capture(pcb, pcb.nextPcbAddress());
    }

    /** Prepara reparación de enlace antes de modificar el PCB o liberar recursos. */
    public static PcbMemoryImage capture(ProcessControlBlock pcb,
            Optional<PcbAddress> next) {
        boolean resident = pcb.state() != ProcessState.READY_SUSPENDED && pcb.state() != ProcessState.BLOCKED_SUSPENDED;
        return capture(pcb, next, resident);
    }

    /** La residencia registrada también distingue una completion terminal procedente de suspensión. */
    public static PcbMemoryImage capture(ProcessControlBlock pcb,
            Optional<PcbAddress> next, boolean resident) {
        Objects.requireNonNull(pcb);
        Objects.requireNonNull(next);
        var context = pcb.cpuContext();
        var accounting = pcb.accounting();
        var stack = pcb.stack().values();
        var values = new PcbValue[PCB_SIZE];
        values[PID.offset()] = number(pcb.processId());
        values[STATE.offset()] = new PcbValue.State(pcb.state());
        values[PC.offset()] = number(context.programCounter());
        values[IR.offset()] = context.instructionRegister().<PcbValue>map(PcbValue.InstructionValue::new).orElse(PcbValue.Empty.INSTANCE);
        values[AC.offset()] = number(context.accumulator());
        values[AX.offset()] = number(context.ax());
        values[BX.offset()] = number(context.bx());
        values[CX.offset()] = number(context.cx());
        values[DX.offset()] = new PcbValue.Register(context.dxValue());
        values[AH.offset()] = number(context.ah());
        values[AL.offset()] = new PcbValue.Register(context.alValue());
        values[EQUAL.offset()] = new PcbValue.Flag(context.conditionFlags().equal());
        values[OVERFLOW.offset()] = new PcbValue.Flag(context.conditionFlags().overflow());
        values[BASE.offset()] = !resident ? PcbValue.Empty.INSTANCE : number(pcb.memoryBounds().base());
        values[LIMIT.offset()] = number(pcb.instructionCount());
        values[PRIORITY.offset()] = number(pcb.priority());
        values[CPU_ID.offset()] = optional(accounting.cpuId());
        values[START.offset()] = timestamp(accounting.startTime());
        values[CPU_TICKS.offset()] = number(accounting.cpuTicks());
        values[FINISH.offset()] = timestamp(accounting.finishTime());
        values[OPEN_FILES.offset()] = new PcbValue.Names(pcb.openFiles().files().stream().sorted().toList());
        values[STACK_TOP.offset()] = number(stack.size() - 1);
        for (int i = 0; i < ProcessStack.CAPACITY; i++) {
            values[PcbField.stackSlot(i).offset()] = i < stack.size() ? number(stack.get(i)) : PcbValue.Empty.INSTANCE;
        }
        values[NEXT_PCB.offset()] = next.<PcbValue>map(address -> number(address.address())).orElse(PcbValue.Empty.INSTANCE);
        return new PcbMemoryImage(pcb, values);
    }

    private static PcbValue number(long value) { return new PcbValue.Number(value); }
    private static PcbValue optional(OptionalInt value) { return value.isPresent() ? number(value.getAsInt()) : PcbValue.Empty.INSTANCE; }
    private static PcbValue timestamp(Optional<Instant> value) {
        return value.<PcbValue>map(PcbValue.Timestamp::new).orElse(PcbValue.Empty.INSTANCE);
    }
}
