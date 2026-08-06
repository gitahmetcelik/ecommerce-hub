package com.ecommercehub.domain.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AES-GCM encryption for channel_connection.encrypted_credentials (Plan §3). pgcrypto is
 * deliberately not used — encryption happens in the application layer so key material never
 * has to live in the database.
 *
 * <p>Ciphertext layout is {@code base64(iv[12] || AES-GCM(plaintext))}; the 128-bit GCM tag
 * is appended by the cipher itself, so tamper detection is built in — decrypt() throws if the
 * ciphertext or the key_version it's paired with don't actually match.
 */
@Service
@EnableConfigurationProperties(CredentialKeyProperties.class)
public class CredentialEncryptionService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final Map<Short, SecretKey> keysByVersion;
    private final short currentKeyVersion;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialEncryptionService(CredentialKeyProperties properties) {
        this.currentKeyVersion = properties.getCurrentKeyVersion();
        this.keysByVersion = properties.getCredentialKeys().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> toSecretKey(e.getValue())));
        if (!keysByVersion.containsKey(currentKeyVersion)) {
            throw new IllegalStateException(
                    "hub.security.current-key-version=" + currentKeyVersion + " has no matching entry in hub.security.credential-keys");
        }
    }

    /** Always encrypts under the current key version — never an older one. */
    public EncryptedCredential encrypt(String plaintext) {
        try {
            SecretKey key = keysByVersion.get(currentKeyVersion);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return new EncryptedCredential(Base64.getEncoder().encodeToString(combined), currentKeyVersion);
        } catch (GeneralSecurityException e) {
            throw new CredentialEncryptionException("Failed to encrypt credential", e);
        }
    }

    /** Decrypts using whichever key version the ciphertext was originally encrypted under. */
    public String decrypt(String ciphertextBase64, short keyVersion) {
        SecretKey key = keysByVersion.get(keyVersion);
        if (key == null) {
            throw new CredentialEncryptionException(
                    "No key configured for version " + keyVersion +
                    " — it was rotated out before this row was re-encrypted under the new key");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(ciphertextBase64);
            byte[] iv = Arrays.copyOfRange(combined, 0, IV_LENGTH_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(combined, IV_LENGTH_BYTES, combined.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new CredentialEncryptionException("Failed to decrypt credential — wrong key version or corrupted ciphertext", e);
        }
    }

    private static SecretKey toSecretKey(String base64Key) {
        return new SecretKeySpec(Base64.getDecoder().decode(base64Key), "AES");
    }
}
