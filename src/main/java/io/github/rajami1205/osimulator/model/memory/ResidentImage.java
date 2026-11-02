package io.github.rajami1205.osimulator.model.memory;

/** Handle tipado de imagen lógica; su PageTable puede contener páginas aún no residentes. */
public sealed interface ResidentImage permits MemoryAllocation, PageTable {
    int size();
}
