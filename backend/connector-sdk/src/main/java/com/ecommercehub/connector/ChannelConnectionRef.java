package com.ecommercehub.connector;

import java.util.UUID;

/**
 * What a connector needs to call a channel — never the encrypted form.
 * Decryption (CredentialEncryptionService, hub-domain) happens before this is built.
 */
public record ChannelConnectionRef(UUID id, UUID organizationId, String channelType, String credentials) {
}
