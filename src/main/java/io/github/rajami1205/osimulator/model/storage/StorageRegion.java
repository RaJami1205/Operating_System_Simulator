package io.github.rajami1205.osimulator.model.storage;

/**
 * Partición física en FILE_INDEX inicial, PROGRAM_DATA y SWAP final; sus límites los establece
 * SecondaryStorage.
 */
public enum StorageRegion {
    FILE_INDEX, PROGRAM_DATA, SWAP
}
