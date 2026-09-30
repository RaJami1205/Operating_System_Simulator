package io.github.rajami1205.osimulator.model.filesystem;

import java.util.Arrays;
import java.util.Optional;
import java.util.Locale;

public enum FileService {
    CREATE(0x3C), OPEN(0x3D), READ(0x4D), WRITE(0x40), DELETE(0x41);
    private final int code;
    FileService(int code) { this.code = code; }
    public int code() { return code; }
    public String canonicalText() { return Integer.toHexString(code).toUpperCase(Locale.ROOT) + "H"; }
    public static Optional<FileService> find(int code) {
        return Arrays.stream(values()).filter(service -> service.code == code).findFirst();
    }
    public static FileService fromCode(int code) {
        return find(code).orElseThrow(() -> new FileSystemException("Unsupported file service: " + code));
    }
}
