package io.github.rajami1205.filesystem.model.identity;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Arrays;

/** Owned snapshot of the v1 credential fields; callers close it after verification or staging. */
public final class Credential implements AutoCloseable {
    public static final int ALGORITHM = 1, ITERATIONS = 600000, SALT_BYTES = 16, HASH_BYTES = 32;
    private final byte[] salt;
    private final byte[] hash;

    public Credential(byte[] salt, byte[] hash) {
        if (salt.length != SALT_BYTES || hash.length != HASH_BYTES)
            throw new IllegalArgumentException("Invalid credential length");
        this.salt = salt.clone();
        this.hash = hash.clone();
    }

    public byte[] saltCopy() { return salt.clone(); }
    public boolean matches(byte[] derived) { return MessageDigest.isEqual(hash, derived); }
    public boolean same(Credential other) {
        return MessageDigest.isEqual(salt, other.salt) & MessageDigest.isEqual(hash, other.hash);
    }
    public void encode(ByteBuffer destination) { destination.put(salt).put(hash); }
    @Override public void close() {
        Arrays.fill(salt, (byte) 0);
        Arrays.fill(hash, (byte) 0);
    }
}
