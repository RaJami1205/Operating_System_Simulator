package io.github.rajami1205.osimulator.model.memory;

import io.github.rajami1205.osimulator.model.process.ProcessControlBlock;
import java.util.Objects;

/** Una celda capturada del PCB. La referencia privada sólo acredita identidad, nunca aporta valores vivos. */
public final class PcbContent implements MemoryContent {
    private final ProcessControlBlock owner;
    private final int ownerPid;
    private final PcbField field;
    private final PcbValue value;

    PcbContent(ProcessControlBlock owner, PcbField field, PcbValue value) {
        this.owner = Objects.requireNonNull(owner);
        this.ownerPid = owner.processId();
        this.field = Objects.requireNonNull(field);
        this.value = Objects.requireNonNull(value);
    }
    public int ownerPid() { return ownerPid; }
    public PcbField field() { return field; }
    public PcbValue value() { return value; }
    public boolean belongsTo(ProcessControlBlock pcb) { return owner == pcb && ownerPid == pcb.processId(); }

    @Override public boolean equals(Object other) {
        return other instanceof PcbContent cell && owner == cell.owner && ownerPid == cell.ownerPid
                && field == cell.field && value.equals(cell.value);
    }
    @Override public int hashCode() { return Objects.hash(System.identityHashCode(owner), ownerPid, field, value); }
}
