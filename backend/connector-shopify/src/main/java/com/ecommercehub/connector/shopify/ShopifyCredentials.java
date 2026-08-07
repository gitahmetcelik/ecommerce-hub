package com.ecommercehub.connector.shopify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link com.ecommercehub.connector.ChannelConnectionRef#credentials()} for Shopify is
 * this JSON, not a bare string like the mock connectors' base URL — Shopify needs the
 * store domain, the static Admin API access token (Plan v5 Faz 4 spike: a "Develop
 * apps" custom app token, `shpat_...`) to call the API, and JSON keeps them from being
 * confused the way a delimiter-joined string would risk.
 *
 * <p>{@code webhookSecret} is deliberately a separate field from {@code accessToken} —
 * an early draft of this connector used the access token as the webhook HMAC secret,
 * which is wrong: Shopify signs webhooks with the app's client secret, a different
 * credential the Admin API access token cannot stand in for. {@code null} when a
 * connection has not been configured for webhook use; {@link ShopifyPlatformConnector#verifySignature}
 * refuses rather than verifying against the wrong key in that case.
 */
record ShopifyCredentials(String storeDomain, String accessToken, String webhookSecret) {

    static ShopifyCredentials parse(ObjectMapper objectMapper, String credentialsJson) {
        try {
            JsonNode node = objectMapper.readTree(credentialsJson);
            JsonNode webhookSecretNode = node.get("webhookSecret");
            return new ShopifyCredentials(node.get("storeDomain").asText(), node.get("accessToken").asText(),
                    webhookSecretNode == null || webhookSecretNode.isNull() ? null : webhookSecretNode.asText());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Shopify credentials must be JSON with storeDomain and accessToken", e);
        }
    }
}
