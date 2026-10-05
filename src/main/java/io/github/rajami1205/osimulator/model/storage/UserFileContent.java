package io.github.rajami1205.osimulator.model.storage;

/**
 * Una unidad UTF-16 de Java por posición simulada de archivo; no equivale necesariamente a un carácter
 * Unicode completo.
 */
public record UserFileContent(char value) implements StorageContent { }
