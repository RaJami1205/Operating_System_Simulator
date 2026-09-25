package io.github.rajami1205.osimulator.model.memory;

/** Contenido tipado de una posición de memoria simulada. */
public sealed interface MemoryContent permits EmptyContent, InstructionContent, PcbContent {}
