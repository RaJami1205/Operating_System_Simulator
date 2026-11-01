package io.github.rajami1205.osimulator.model.memory;

/** Handle tipado de una imagen completa; no implica contigüidad física. */
public sealed interface ResidentImage permits MemoryAllocation, PageTable {
    int size();
}
