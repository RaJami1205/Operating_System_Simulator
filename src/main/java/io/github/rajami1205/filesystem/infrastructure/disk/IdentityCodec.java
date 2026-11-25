package io.github.rajami1205.filesystem.infrastructure.disk;

import io.github.rajami1205.filesystem.model.disk.DiskGeometry;
import io.github.rajami1205.filesystem.model.identity.Credential;
import io.github.rajami1205.filesystem.model.identity.Identity.Group;
import io.github.rajami1205.filesystem.model.identity.Identity.User;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class IdentityCodec {
    private IdentityCodec() {}

    public enum Table {
        USERS(DiskGeometry.USER_COUNT, DiskGeometry.USER_BYTES),
        GROUPS(DiskGeometry.GROUP_COUNT, DiskGeometry.GROUP_BYTES);

        public final int capacity, width;
        Table(int capacity, int width) { this.capacity = capacity; this.width = width; }
        private long offset(int slot) {
            if (slot < 0 || slot >= capacity) throw new IllegalArgumentException("Invalid identity slot");
            return Math.multiplyExact((long) slot, width);
        }
        public int block(DiskGeometry g, int slot) {
            int start = this == USERS ? g.userStart() : g.groupStart();
            return Math.addExact(start, Math.toIntExact(offset(slot) / g.blockSize()));
        }
        public ByteBuffer record(DiskGeometry g, byte[] page, int slot) {
            if (page.length != g.blockSize()) throw new IllegalArgumentException("Invalid identity page");
            return ByteBuffer.wrap(page).slice((int) (offset(slot) % g.blockSize()), width).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    private static void zeros(ByteBuffer b, int start, int end) {
        for (int i = start; i < end; i++)
            if (b.get(i) != 0) throw new IllegalArgumentException("Nonzero identity padding");
    }
    private static boolean free(ByteBuffer b) {
        if (b.get(0) == 1) return false;
        zeros(b, 0, b.capacity());
        return true;
    }
    private static String string(ByteBuffer b, int at, int size, int length) {
        if (length < 1 || length > size) throw new IllegalArgumentException("Invalid identity string length");
        zeros(b, at + length, at + size);
        try { return StandardCharsets.UTF_8.newDecoder().decode(b.slice(at, length)).toString(); }
        catch (CharacterCodingException e) { throw new IllegalArgumentException("Invalid identity UTF-8", e); }
    }

    public static User user(DiskGeometry g, byte[] page, int slot) {
        var b = Table.USERS.record(g, page, slot);
        if (free(b)) return null;
        if (b.get(1) != Credential.ALGORITHM || b.getInt(16) != Credential.ITERATIONS)
            throw new IllegalArgumentException("Unsupported credential parameters");
        zeros(b, 2, 4);
        zeros(b, 240, 256);
        if (b.getInt(4) != slot || b.getInt(12) >= g.fcbCapacity()) throw new IllegalArgumentException("Invalid user slot or HOME");
        return new User(slot, b.getInt(8), b.getInt(12), string(b, 24, 32, Short.toUnsignedInt(b.getShort(20))),
                string(b, 56, 128, Short.toUnsignedInt(b.getShort(22))), b.getLong(232));
    }

    public static Credential credential(DiskGeometry g, byte[] page, int slot) {
        if (user(g, page, slot) == null) throw new IllegalArgumentException("Inactive user");
        var b = Table.USERS.record(g, page, slot);
        byte[] salt = new byte[Credential.SALT_BYTES], hash = new byte[Credential.HASH_BYTES];
        try {
            b.get(184, salt).get(200, hash);
            return new Credential(salt, hash);
        } finally {
            Arrays.fill(salt, (byte) 0);
            Arrays.fill(hash, (byte) 0);
        }
    }

    public static Group group(DiskGeometry g, byte[] page, int slot) {
        var b = Table.GROUPS.record(g, page, slot);
        if (free(b)) return null;
        zeros(b, 1, 4);
        zeros(b, 10, 12);
        zeros(b, 44, 48);
        zeros(b, 56, 128);
        if (b.getInt(4) != slot) throw new IllegalArgumentException("Invalid group slot");
        return new Group(slot, string(b, 12, 32, Short.toUnsignedInt(b.getShort(8))), b.getLong(48));
    }

    private static void clear(ByteBuffer b) {
        Arrays.fill(b.array(), b.arrayOffset(), b.arrayOffset() + b.capacity(), (byte) 0);
    }

    public static void writeUser(DiskGeometry g, byte[] page, User u, Credential credential) {
        if (u.home() >= g.fcbCapacity()) throw new IllegalArgumentException("Invalid HOME slot");
        var b = Table.USERS.record(g, page, u.uid());
        clear(b);
        byte[] name = u.name().getBytes(StandardCharsets.US_ASCII);
        byte[] full = u.fullName().getBytes(StandardCharsets.UTF_8);
        b.put(0, (byte) 1).put(1, (byte) Credential.ALGORITHM).putInt(4, u.uid()).putInt(8, u.gid())
                .putInt(12, u.home()).putInt(16, Credential.ITERATIONS).putShort(20, (short) name.length)
                .putShort(22, (short) full.length).put(24, name).put(56, full).putLong(232, u.created());
        credential.encode(b.position(184));
    }

    public static void writeGroup(DiskGeometry g, byte[] page, Group group) {
        var b = Table.GROUPS.record(g, page, group.gid());
        clear(b);
        byte[] name = group.name().getBytes(StandardCharsets.US_ASCII);
        b.put(0, (byte) 1).putInt(4, group.gid()).putShort(8, (short) name.length).put(12, name).putLong(48, group.created());
    }
}
