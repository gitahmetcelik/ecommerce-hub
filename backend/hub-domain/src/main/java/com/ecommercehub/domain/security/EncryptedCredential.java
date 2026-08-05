package com.ecommercehub.domain.security;

/** Ciphertext plus the key version it was encrypted under — the pair stored in channel_connection. */
public record EncryptedCredential(String ciphertextBase64, short keyVersion) {
}
