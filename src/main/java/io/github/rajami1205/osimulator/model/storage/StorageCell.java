package io.github.rajami1205.osimulator.model.storage;

import java.util.Objects;

public record StorageCell(int address, StorageRegion region, StorageContent content) {
    public StorageCell {
        if (address < 0) throw new IllegalArgumentException("Storage address must not be negative");
        Objects.requireNonNull(region, "region must not be null");
        Objects.requireNonNull(content, "content must not be null");
    }
}
