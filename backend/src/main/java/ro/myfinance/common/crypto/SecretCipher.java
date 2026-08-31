package ro.myfinance.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM envelope encryption for per-tenant secrets (SMTP passwords, WhatsApp provider tokens). The
 * master key is a base64-encoded 32-byte key from {@code myfinance.secret.key} (env
 * {@code MYFINANCE_SECRET_KEY}) — it lives only in the app's config, never in the database. Stored form is
 * {@code base64( 12-byte IV || ciphertext+GCM-tag )} with a fresh random IV per encryption, so identical
 * plaintexts encrypt differently and tampering is detected. Secrets are decrypted only in-memory at
 * send-time and are never returned to the client.
 */
@Component
public class SecretCipher {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Null when {@code myfinance.secret.key} is unset — any encrypt/decrypt then fails closed. */
    private final byte[] key;

    public SecretCipher(@Value("${myfinance.secret.key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
        } else {
            byte[] k = Base64.getDecoder().decode(base64Key.trim());
            if (k.length != 32) {
                throw new IllegalStateException(
                        "myfinance.secret.key must be a base64-encoded 32-byte (256-bit) AES key");
            }
            this.key = k;
        }
    }

    /** Whether a master key is configured — settings that store secrets require this to be true. */
    public boolean isConfigured() {
        return key != null;
    }

    /** Encrypt a secret to its stored form; passes {@code null} through unchanged (a cleared secret). */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        requireKey();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Secret encryption failed", e);
        }
    }

    /** Decrypt a stored secret back to plaintext; passes {@code null} through unchanged. */
    public String decrypt(String stored) {
        if (stored == null) {
            return null;
        }
        requireKey();
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            byte[] iv = Arrays.copyOfRange(all, 0, IV_BYTES);
            byte[] ct = Arrays.copyOfRange(all, IV_BYTES, all.length);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Secret decryption failed", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "myfinance.secret.key is not configured — cannot store or read per-tenant secrets");
        }
    }
}
