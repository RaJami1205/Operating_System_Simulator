package io.github.rajami1205.filesystem.model.disk;

import java.nio.charset.StandardCharsets;

public record DirectoryEntry(int child, String name) {
    public DirectoryEntry {
        if (child <= 0) throw new IllegalArgumentException("Invalid ordinary child slot");
        validateName(name);
    }

    public static void validateName(String name) {
        utf8Length(name, 255);
        if (name.isEmpty() || name.equals(".") || name.equals("..")
                || name.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '/' || c == '\\'
                    || c == '*' || c == '?'))
            throw new IllegalArgumentException("Invalid directory entry name");
    }

    public static int utf8Length(String text, int limit) {
        // Each valid UTF-16 unit requires at least one UTF-8 byte.
        if (text.length() > limit || !StandardCharsets.UTF_8.newEncoder().canEncode(text))
            throw new IllegalArgumentException("Invalid or overlong UTF-8 text");
        int length = text.getBytes(StandardCharsets.UTF_8).length;
        if (length > limit) throw new IllegalArgumentException("UTF-8 byte limit exceeded");
        return length;
    }
}
