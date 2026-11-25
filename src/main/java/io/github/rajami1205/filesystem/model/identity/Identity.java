package io.github.rajami1205.filesystem.model.identity;

import io.github.rajami1205.filesystem.model.disk.DirectoryEntry;
import io.github.rajami1205.filesystem.model.disk.DiskGeometry;
import io.github.rajami1205.filesystem.model.disk.Fcb;
import java.util.regex.Pattern;

public final class Identity {
    private Identity() {}
    private static final Pattern NAME = Pattern.compile("[a-z_][a-z0-9_-]{0,31}");

    public static void name(String value) {
        if (value == null || !NAME.matcher(value).matches()) throw new IllegalArgumentException("Invalid identity name");
    }

    public static void fullName(String value) {
        if (value == null || DirectoryEntry.utf8Length(value, 128) == 0 || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid full name");
    }

    public record Group(int gid, String name, long created) {
        public Group {
            Identity.name(name);
            if (gid < 0 || gid >= DiskGeometry.GROUP_COUNT) throw new IllegalArgumentException("Invalid GID");
        }
    }

    /** Non-secret persistent metadata. Authority is established separately by authentication. */
    public record User(int uid, int gid, int home, String name, String fullName, long created) {
        public User {
            Identity.name(name);
            Identity.fullName(fullName);
            if (uid < 0 || uid >= DiskGeometry.USER_COUNT || gid < 0 || gid >= DiskGeometry.GROUP_COUNT
                    || (uid == 0) != (gid == 0) || home <= 0)
                throw new IllegalArgumentException("Invalid user identifiers");
        }

        public void require(Fcb resource, int mask) {
            if (uid == 0) return;
            int bits = 0;
            if (resource.owner() == uid) bits = resource.mode() >> 3;
            else if (resource.group() == gid || resource.group() == 1) bits = resource.mode() & 7;
            if ((bits & mask) != mask) throw new SecurityException("Access denied");
        }
    }
}
