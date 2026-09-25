package io.github.rajami1205.osimulator.model.storage;

/** Contenido semántico tipado de una posición de almacenamiento simulado. */
public sealed interface StorageContent permits EmptyStorageContent, StoredInstructionContent, FileIndexEntry {}
