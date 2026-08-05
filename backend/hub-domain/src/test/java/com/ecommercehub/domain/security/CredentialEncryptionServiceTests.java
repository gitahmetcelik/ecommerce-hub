package com.ecommercehub.domain.security;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CredentialEncryptionServiceTests {

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static CredentialKeyProperties propertiesWithVersions(short current, Map<Short, String> keys) {
        CredentialKeyProperties properties = new CredentialKeyProperties();
        properties.setCurrentKeyVersion(current);
        properties.setCredentialKeys(keys);
        return properties;
    }

    @Test
    void encryptThenDecryptReturnsTheOriginalPlaintext() {
        CredentialEncryptionService service = new CredentialEncryptionService(
                propertiesWithVersions((short) 1, Map.of((short) 1, randomKey())));

        EncryptedCredential encrypted = service.encrypt("super-secret-api-token");

        assertThat(encrypted.keyVersion()).isEqualTo((short) 1);
        assertThat(encrypted.ciphertextBase64()).isNotEqualTo("super-secret-api-token");
        assertThat(service.decrypt(encrypted.ciphertextBase64(), encrypted.keyVersion()))
                .isEqualTo("super-secret-api-token");
    }

    @Test
    void encryptingTheSamePlaintextTwiceProducesDifferentCiphertext() {
        CredentialEncryptionService service = new CredentialEncryptionService(
                propertiesWithVersions((short) 1, Map.of((short) 1, randomKey())));

        EncryptedCredential first = service.encrypt("same-value");
        EncryptedCredential second = service.encrypt("same-value");

        assertThat(first.ciphertextBase64())
                .withFailMessage("A fresh random IV per call must make ciphertext non-deterministic even for identical plaintext")
                .isNotEqualTo(second.ciphertextBase64());
    }

    @Test
    @SuppressWarnings("DataFlowIssue")
    void keyRotation_oldCiphertextStaysReadableUnderItsOriginalVersionAfterCurrentVersionMovesOn() {
        String v1Key = randomKey();
        String v2Key = randomKey();

        CredentialEncryptionService beforeRotation = new CredentialEncryptionService(
                propertiesWithVersions((short) 1, Map.of((short) 1, v1Key)));
        EncryptedCredential encryptedUnderV1 = beforeRotation.encrypt("rotate-me");

        // Rotation: v2 becomes current, v1 stays configured so old rows are still readable.
        CredentialEncryptionService afterRotation = new CredentialEncryptionService(
                propertiesWithVersions((short) 2, Map.of((short) 1, v1Key, (short) 2, v2Key)));

        assertThat(afterRotation.decrypt(encryptedUnderV1.ciphertextBase64(), encryptedUnderV1.keyVersion()))
                .isEqualTo("rotate-me");

        EncryptedCredential encryptedAfterRotation = afterRotation.encrypt("new-value");
        assertThat(encryptedAfterRotation.keyVersion())
                .withFailMessage("Encryption must always use the current key version, never an older one")
                .isEqualTo((short) 2);
    }

    @Test
    void decryptingWithAKeyVersionThatWasRotatedOutFailsLoudly() {
        CredentialEncryptionService service = new CredentialEncryptionService(
                propertiesWithVersions((short) 1, Map.of((short) 1, randomKey())));
        EncryptedCredential encrypted = service.encrypt("value");

        CredentialEncryptionService withoutV1 = new CredentialEncryptionService(
                propertiesWithVersions((short) 2, Map.of((short) 2, randomKey())));

        assertThatThrownBy(() -> withoutV1.decrypt(encrypted.ciphertextBase64(), encrypted.keyVersion()))
                .isInstanceOf(CredentialEncryptionException.class)
                .hasMessageContaining("No key configured for version");
    }

    @Test
    void tamperedCiphertextFailsToDecryptInsteadOfReturningGarbage() {
        CredentialEncryptionService service = new CredentialEncryptionService(
                propertiesWithVersions((short) 1, Map.of((short) 1, randomKey())));
        EncryptedCredential encrypted = service.encrypt("value");

        byte[] tampered = Base64.getDecoder().decode(encrypted.ciphertextBase64());
        tampered[tampered.length - 1] ^= 0x01;
        String tamperedBase64 = Base64.getEncoder().encodeToString(tampered);

        assertThatThrownBy(() -> service.decrypt(tamperedBase64, encrypted.keyVersion()))
                .isInstanceOf(CredentialEncryptionException.class);
    }

    @Test
    void constructorFailsFastIfCurrentKeyVersionHasNoMatchingKey() {
        assertThatThrownBy(() -> new CredentialEncryptionService(
                propertiesWithVersions((short) 2, Map.of((short) 1, randomKey()))))
                .isInstanceOf(IllegalStateException.class);
    }
}
