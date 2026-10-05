package io.github.rajami1205.osimulator.model.filesystem;

import java.util.Arrays;
import java.util.Optional;
import java.util.Locale;

/**
 * Selectores de AH para INT21: CREATE 3CH, OPEN 3DH, READ 4DH, WRITE 40H y DELETE 41H; no implementa una
 * ABI completa de DOS.
 */
public enum FileService {
    CREATE(0x3C), OPEN(0x3D), READ(0x4D), WRITE(0x40), DELETE(0x41);
    private final int code;
    /** Asocia el selector hexadecimal usado en AH con el servicio de filesystem. */
    FileService(int code) { this.code = code; }
    /** Devuelve el selector numérico que debe contener AH para el servicio. */
    public int code() { return code; }
    /** Representa el selector de AH en hexadecimal con sufijo H para formatting ASM. */
    public String canonicalText() { return Integer.toHexString(code).toUpperCase(Locale.ROOT) + "H"; }
    /** Busca un servicio por código de AH sin lanzar excepción si no está implementado. */
    public static Optional<FileService> find(int code) {
        return Arrays.stream(values()).filter(service -> service.code == code).findFirst();
    }
    /** Resuelve el selector o genera FileSystemException para un servicio no soportado. */
    public static FileService fromCode(int code) {
        return find(code).orElseThrow(() -> new FileSystemException("Unsupported file service: " + code));
    }
}
