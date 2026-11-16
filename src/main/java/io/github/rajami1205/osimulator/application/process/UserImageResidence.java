package io.github.rajami1205.osimulator.application.process;

import io.github.rajami1205.osimulator.model.memory.MemoryAllocation;
import io.github.rajami1205.osimulator.model.memory.MemoryRegion;
import io.github.rajami1205.osimulator.model.memory.PageTable;
import io.github.rajami1205.osimulator.model.memory.ResidentImage;
import io.github.rajami1205.osimulator.model.storage.StorageAllocation;
import io.github.rajami1205.osimulator.model.storage.PageBacking;
import java.util.Objects;

/**
 * Residencia exclusiva de la imagen en USER o VIRTUAL_MEMORY mediante su handle original; nunca reconstruye
 * identidad a partir de Base/Limit.
 */
public sealed interface UserImageResidence {
    record Demand(PageTable table, PageBacking backing) implements UserImageResidence {
        public Demand { Objects.requireNonNull(backing).validate(Objects.requireNonNull(table)); }
    }
    record DemandSuspended(PageTable table, PageBacking backing) implements UserImageResidence {
        public DemandSuspended {
            Objects.requireNonNull(backing).validate(Objects.requireNonNull(table));
            if (!table.frames().isEmpty()) throw new IllegalArgumentException("Suspended image retains frames");
        }
    }
    default boolean suspended() { return this instanceof Suspended || this instanceof DemandSuspended; }
    record Paged(PageTable table) implements UserImageResidence {
        public Paged {
            Objects.requireNonNull(table);
            if (table.loadMode() != io.github.rajami1205.osimulator.model.memory.PagingLoadMode.FULLY_RESIDENT)
                throw new IllegalArgumentException("Demand residence requires backing");
        }
    }
    default ResidentImage residentImage() {
        return switch (this) {
            case Resident resident -> resident.allocation();
            case Paged paged -> paged.table();
            case Demand demand -> demand.table();
            case DemandSuspended ignored -> throw new IllegalStateException("Suspended demand image");
            case Suspended ignored -> throw new IllegalStateException("Suspended image is not resident");
        };
    }
    static UserImageResidence resident(ResidentImage image) {
        return switch (image) {
            case MemoryAllocation allocation -> new Resident(allocation);
            case PageTable table -> new Paged(table);
        };
    }
    /** Imagen con reserva física USER; conserva el handle original requerido por fetch y release. */
    record Resident(MemoryAllocation allocation) implements UserImageResidence {
        /** Exige un handle no nulo perteneciente a la región USER. */
        public Resident {
            Objects.requireNonNull(allocation);
            if (allocation.region() != MemoryRegion.USER) throw new IllegalArgumentException("USER allocation required");
        }
    }
    /** Imagen respaldada en VIRTUAL_MEMORY; no mantiene una allocation física USER. */
    record Suspended(StorageAllocation allocation) implements UserImageResidence {
        /** Exige el handle no nulo que identifica la imagen en VIRTUAL_MEMORY. */
        public Suspended { Objects.requireNonNull(allocation); }
    }
}
