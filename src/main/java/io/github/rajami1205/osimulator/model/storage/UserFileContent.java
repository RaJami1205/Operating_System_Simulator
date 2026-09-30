package io.github.rajami1205.osimulator.model.storage;

/** One Java UTF-16 code unit per simulated storage position. */
public record UserFileContent(char value) implements StorageContent { }
