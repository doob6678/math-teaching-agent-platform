package com.doob.mathagent.auth.service;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Service;

/**
 * Hashes and verifies passwords for locally registered accounts.
 */
@Service
public class PasswordHashService {

    private static final String PREFIX = "pbkdf2_sha256";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int SALT_BYTES = 16;
    private final SecureRandom random = new SecureRandom();

    /**
     * Encodes a raw password using PBKDF2 with a per-account random salt.
     *
     * @param rawPassword plaintext password
     * @return encoded password string
     */
    public String encode(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword, salt, ITERATIONS);
        return PREFIX + "$" + ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * Verifies a raw password against a stored encoded password.
     *
     * @param rawPassword plaintext password
     * @param encodedPassword encoded password string
     * @return true when the password matches
     */
    public boolean matches(String rawPassword, String encodedPassword) {
        if (rawPassword == null || encodedPassword == null || encodedPassword.isBlank()) {
            return false;
        }
        if (!encodedPassword.startsWith(PREFIX + "$")) {
            return false;
        }
        String[] parts = encodedPassword.split("\\$", -1);
        if (parts.length != 4) {
            return false;
        }
        int iterations = Integer.parseInt(parts[1]);
        byte[] salt = Base64.getDecoder().decode(parts[2]);
        byte[] expected = Base64.getDecoder().decode(parts[3]);
        byte[] actual = pbkdf2(rawPassword, salt, iterations);
        return constantTimeEquals(expected, actual);
    }

    /**
     * Runs PBKDF2-HMAC-SHA256.
     */
    private static byte[] pbkdf2(String rawPassword, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new IllegalStateException("Password hashing is unavailable", e);
        }
    }

    /**
     * Compares two byte arrays without early exit.
     */
    private static boolean constantTimeEquals(byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            return false;
        }
        int diff = 0;
        for (int index = 0; index < expected.length; index++) {
            diff |= expected[index] ^ actual[index];
        }
        return diff == 0;
    }
}
