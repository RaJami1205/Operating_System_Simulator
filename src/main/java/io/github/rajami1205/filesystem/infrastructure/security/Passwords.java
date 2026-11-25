package io.github.rajami1205.filesystem.infrastructure.security;

import io.github.rajami1205.filesystem.model.identity.Credential;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class Passwords {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Passwords() {}

    private static char[] copy(char[] password) {
        if (password == null || password.length < 1 || password.length > 128)
            throw new IllegalArgumentException("Invalid password");
        return password.clone();
    }

    private static void validate(char[] password) {
        for (int i = 0; i < password.length; i++) {
            char c = password[i];
            if (Character.isHighSurrogate(c)) {
                if (++i >= password.length || !Character.isLowSurrogate(password[i]))
                    throw new IllegalArgumentException("Invalid password");
            } else if (Character.isLowSurrogate(c) || Character.isISOControl(c))
                throw new IllegalArgumentException("Invalid password");
        }
    }

    private static byte[] derive(char[] password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, Credential.ITERATIONS, Credential.HASH_BYTES * 8);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Credential derivation unavailable", e);
        } finally { spec.clearPassword(); }
    }

    public static Credential establish(char[] password, char[] confirmation) {
        char[] input = copy(password);
        byte[] salt = new byte[Credential.SALT_BYTES];
        byte[] hash = null;
        try {
            validate(input);
            if (!Arrays.equals(input, confirmation)) throw new IllegalArgumentException("Password confirmation mismatch");
            RANDOM.nextBytes(salt);
            hash = derive(input, salt);
            return new Credential(salt, hash);
        } finally {
            Arrays.fill(input, '\0');
            Arrays.fill(salt, (byte) 0);
            if (hash != null) Arrays.fill(hash, (byte) 0);
        }
    }

    public static boolean verify(Credential snapshot, char[] password) {
        if (password == null || password.length < 1 || password.length > 128) return false;
        char[] input = copy(password);
        byte[] salt = snapshot.saltCopy();
        byte[] hash = null;
        try {
            try { validate(input); }
            catch (IllegalArgumentException invalid) { return false; }
            hash = derive(input, salt);
            return snapshot.matches(hash);
        } finally {
            Arrays.fill(input, '\0');
            Arrays.fill(salt, (byte) 0);
            if (hash != null) Arrays.fill(hash, (byte) 0);
        }
    }
}
