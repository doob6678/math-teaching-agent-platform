package com.doob.mathagent.feishu;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** AES-GCM envelope used for Feishu access and refresh tokens at rest. The key is platform configuration only. */
public final class FeishuCredentialCipher {
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /** Creates a cipher from a base64 encoded 256-bit platform key. */
    public FeishuCredentialCipher(String base64Key) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key == null ? "" : base64Key);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("FEISHU_TOKEN_ENCRYPTION_KEY must be base64", exception);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalArgumentException("FEISHU_TOKEN_ENCRYPTION_KEY must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(decoded, "AES");
    }

    /** Encrypts one token and returns nonce+ciphertext as base64; plaintext never enters logs or messages. */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) throw new IllegalArgumentException("token is required");
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt Feishu credential", exception);
        }
    }

    /** Decrypts a value previously produced by {@link #encrypt(String)}. */
    public String decrypt(String encoded) {
        try {
            byte[] packed = Base64.getDecoder().decode(encoded);
            if (packed.length <= NONCE_BYTES) throw new IllegalArgumentException("invalid encrypted credential");
            byte[] nonce = java.util.Arrays.copyOfRange(packed, 0, NONCE_BYTES);
            byte[] ciphertext = java.util.Arrays.copyOfRange(packed, NONCE_BYTES, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid encrypted Feishu credential", exception);
        }
    }
}
