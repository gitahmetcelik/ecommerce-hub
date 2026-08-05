package com.ecommercehub.domain.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Master key material for channel_connection.encrypted_credentials (plan §3, AES-GCM).
 * Keys are versioned so rotation never breaks data encrypted under an older key: add a new
 * entry, bump currentKeyVersion, and existing rows stay decryptable via their own stored
 * key_version until something re-encrypts them.
 */
@ConfigurationProperties(prefix = "hub.security")
public class CredentialKeyProperties {

    private short currentKeyVersion = 1;

    /** version -> base64-encoded 256-bit AES key. */
    private Map<Short, String> credentialKeys = new HashMap<>();

    public short getCurrentKeyVersion() {
        return currentKeyVersion;
    }

    public void setCurrentKeyVersion(short currentKeyVersion) {
        this.currentKeyVersion = currentKeyVersion;
    }

    public Map<Short, String> getCredentialKeys() {
        return credentialKeys;
    }

    public void setCredentialKeys(Map<Short, String> credentialKeys) {
        this.credentialKeys = credentialKeys;
    }
}
