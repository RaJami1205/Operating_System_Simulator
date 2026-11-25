package io.github.rajami1205.filesystem.infrastructure.disk;

import io.github.rajami1205.filesystem.infrastructure.security.Passwords;
import io.github.rajami1205.filesystem.model.disk.*;
import io.github.rajami1205.filesystem.model.identity.Credential;
import io.github.rajami1205.filesystem.model.identity.Identity.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class IdentityCodecTest {
    private final DiskGeometry g = new DiskGeometry(1 << 20, 512);
    private final HexFormat hex = HexFormat.of();

    @Test void userGoldenBytesAndValidation() {
        byte[] golden = new byte[512];
        Arrays.fill(golden, 0, 256, (byte) 7);
        byte[] head = hex.parseHex("01010000010000000200000003000000c027090003000400");
        System.arraycopy(head, 0, golden, 256, head.length);
        System.arraycopy(hex.parseHex("626f62"), 0, golden, 280, 3);
        System.arraycopy(hex.parseHex("20c3a920"), 0, golden, 312, 4);
        for (int i = 0; i < 48; i++) golden[440 + i] = (byte) i;
        System.arraycopy(hex.parseHex("0807060504030201"), 0, golden, 488, 8);
        var user = new User(1, 2, 3, "bob", " é ", 0x0102030405060708L);
        assertEquals(user, IdentityCodec.user(g, golden, 1));
        byte[] encoded = new byte[512];
        Arrays.fill(encoded, 0, 256, (byte) 7);
        try (var c = IdentityCodec.credential(g, golden, 1)) {
            IdentityCodec.writeUser(g, encoded, user, c);
        }
        assertArrayEquals(golden, encoded);
        for (int offset : new int[]{0,1,2,4,8,13,16,21,23,24,27,57,60,240}) {
            byte[] bad = golden.clone();
            bad[256 + offset] ^= (byte) 0xff;
            assertThrows(IllegalArgumentException.class, () -> IdentityCodec.credential(g, bad, 1), "offset " + offset);
        }
        byte[] free = new byte[512];
        assertNull(IdentityCodec.user(g, free, 0));
        free[200] = 1;
        assertThrows(IllegalArgumentException.class, () -> IdentityCodec.user(g, free, 0));
    }

    @Test void groupGoldenBytes() {
        byte[] golden = new byte[512];
        byte[] head = hex.parseHex("010000000200000003000000646576");
        System.arraycopy(head, 0, golden, 256, head.length);
        System.arraycopy(hex.parseHex("0807060504030201"), 0, golden, 304, 8);
        var group = new Group(2, "dev", 0x0102030405060708L);
        assertEquals(group, IdentityCodec.group(g, golden, 2));
        var encoded = new byte[512];
        IdentityCodec.writeGroup(g, encoded, group);
        assertArrayEquals(golden, encoded);
        for (int offset : new int[]{0,1,4,9,10,12,15,44,56}) {
            byte[] bad = golden.clone();
            bad[256 + offset] ^= (byte) 0xff;
            assertThrows(IllegalArgumentException.class, () -> IdentityCodec.group(g, bad, 2));
        }
        assertNull(IdentityCodec.group(g, golden, 1));
        golden[128 + 56] = 1;
        assertThrows(IllegalArgumentException.class, () -> IdentityCodec.group(g, golden, 1));
    }

    @Test void knownPbkdf2VectorAndInputBoundaries() {
        byte[] salt = hex.parseHex("000102030405060708090a0b0c0d0e0f");
        byte[] hash = hex.parseHex("3bc37118e625093e9b79ed08930ea7af7389591233fdd92dddf369371e60dbc0");
        try (var c = new Credential(salt, hash)) {
            Arrays.fill(hash, (byte) 0);
            Arrays.fill(salt, (byte) 0);
            assertTrue(Passwords.verify(c, "password".toCharArray()));
            assertFalse(Passwords.verify(c, "Password".toCharArray()));
            assertFalse(c.toString().contains("3bc37118"));
        }
        for (String bad : List.of("", "a".repeat(129), "\ud800", "\udc00", "a\u0085"))
            assertThrows(IllegalArgumentException.class, () -> Passwords.establish(bad.toCharArray(), bad.toCharArray()));
        assertThrows(IllegalArgumentException.class, () -> Passwords.establish(" a".toCharArray(), "a".toCharArray()));
        char[] password = (" ".repeat(126) + "😀").toCharArray();
        try (var a = Passwords.establish(password, password); var b = Passwords.establish(password, password)) {
            assertFalse(Arrays.equals(a.saltCopy(), b.saltCopy()));
            assertTrue(Passwords.verify(a, password));
        }
        assertDoesNotThrow(() -> new User(1, 1, 3, "_a-b0", "é".repeat(64), 0));
        for (String name : List.of("", "é".repeat(65), "\ud800", "ab\u009b"))
            assertThrows(IllegalArgumentException.class, () -> new User(1, 1, 3, "u", name, 0));
        for (String name : List.of("A", "0user", "a".repeat(33), "a b"))
            assertThrows(IllegalArgumentException.class, () -> new Group(2, name, 0));
    }

    @Test void dacOwnerPrecedenceAndEffectiveGroups() {
        for (int mode = 0; mode < 64; mode++) {
            Fcb f = new Fcb(Fcb.Type.DIRECTORY, mode, 1, 3, 2, 0, -1, 0, 0, 0);
            for (int mask : new int[]{1, 2, 3, 4, 5, 6}) {
                check(new User(0,0,1,"root","root",0), f, mask, true);
                check(new User(3,2,4,"owner","owner",0), f, mask, ((mode >> 3) & mask) == mask);
                check(new User(4,2,5,"member","member",0), f, mask, (mode & mask) == mask);
                check(new User(4,3,5,"other","other",0), f, mask, false);
            }
        }
        var member = new User(3,2,4,"member","member",0);
        check(member, new Fcb(Fcb.Type.DIRECTORY, 005, 1, 2, 1, 0, -1, 0, 0, 0), 5, true);
    }

    private void check(User user, Fcb fcb, int mask, boolean expected) {
        if (expected) assertDoesNotThrow(() -> user.require(fcb, mask));
        else assertThrows(SecurityException.class, () -> user.require(fcb, mask));
    }
}
