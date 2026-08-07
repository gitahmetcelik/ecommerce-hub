package com.ecommercehub.connector.shopify;

import com.ecommercehub.connector.CallIntentRef;
import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.connector.ConnectorContractTest;
import com.ecommercehub.connector.CredentialStatus;
import com.ecommercehub.connector.PlatformConnector;
import com.ecommercehub.connector.RefundRequest;
import com.ecommercehub.connector.RefundResult;
import com.ecommercehub.connector.ShipmentRequest;
import com.ecommercehub.connector.ShipmentResult;
import com.ecommercehub.connector.SignatureVerification;
import com.ecommercehub.connector.RawRequest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan v5 Faz 4: real Shopify has no way to inject a per-item failure or a 429 on
 * demand (the contract's own hooks need both), so WireMock stands in — stubbed with
 * the exact response shapes the Faz 4 spike observed calling the real Admin GraphQL
 * API against a live dev store (atomic-per-call inventory writes, cursor pagination,
 * THROTTLED-in-200 vs. plain 429, all confirmed live; see
 * {@code docs/kanal-arastirmasi.md}'s "§4 spike sonuçları" and this connector's own
 * javadoc for exactly which shapes are live-verified vs. inferred from docs).
 */
class ShopifyPlatformConnectorContractTest extends ConnectorContractTest {

    private static final String WEBHOOK_SECRET = "test-webhook-secret";
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();

    private static WireMockServer wireMock;
    private static ObjectMapper objectMapper;
    private static PlatformConnector connector;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        objectMapper = new ObjectMapper();
        connector = new ShopifyPlatformConnector(HttpClient.newHttpClient(), objectMapper);
    }

    @AfterAll
    static void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetBeforeEachTest() {
        resetScenarios();
    }

    @Override
    protected PlatformConnector connector() {
        return connector;
    }

    @Override
    protected ChannelConnectionRef connection() {
        String credentialsJson = "{\"storeDomain\":\"http://localhost:" + wireMock.port()
                + "\",\"accessToken\":\"test-token\",\"webhookSecret\":\"" + WEBHOOK_SECRET + "\"}";
        return new ChannelConnectionRef(CONNECTION_ID, ORG_ID, ShopifyPlatformConnector.CHANNEL_TYPE, credentialsJson);
    }

    @Override
    protected void resetScenarios() {
        wireMock.resetAll();
        registerBaselineStubs();
    }

    /**
     * Registered at high priority (1) so it wins over the generic "any inventory
     * batch succeeds" stub for the specific 3-item request the contract test sends —
     * the retry-without-the-bad-item call (2 items, no "inv-1") falls through to the
     * generic stub instead, exactly reproducing what the connector actually has to do
     * against real Shopify (class javadoc: atomic-per-call, not atomic-per-item).
     */
    @Override
    protected void givenPartialFailureForSkus(Set<String> skus) {
        assertThat(skus).containsExactly("SKU-1");
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("inventorySetQuantities"))
                .withRequestBody(containing("inv-1"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"data":{"inventorySetQuantities":{"inventoryAdjustmentGroup":null,
                                "userErrors":[{"field":["input","quantities","1","inventoryItemId"],
                                "message":"The specified inventory item could not be found."}]}}}
                                """)));
    }

    @Override
    protected void givenChannelIsRateLimited() {
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("orders(first"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(429)));
    }

    @Override
    protected String signBody(byte[] bodyBytes) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(bodyBytes));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected String signatureHeaderName() {
        return "X-Shopify-Hmac-Sha256";
    }

    @Override
    protected java.time.Instant anExistingOrderEventAt() {
        return java.time.Instant.parse("2020-01-01T00:00:00Z");
    }

    @Override
    protected String aNonAsciiTitleFromCatalog() {
        return "Türkçe Ürün İçeriği 😀";
    }

    // =========================================================================
    // Shopify-specific coverage beyond the inherited contract
    // =========================================================================

    @Test
    @DisplayName("createShipment finds the order's open fulfillment order and creates a fulfillment against it")
    void createShipmentUsesOpenFulfillmentOrders() {
        ShipmentResult result = connector.createShipment(connection(), new ShipmentRequest("gid://shopify/Order/1"),
                new CallIntentRef(UUID.randomUUID(), "intent-1"));

        assertThat(result.channelShipmentId()).isEqualTo("gid://shopify/Fulfillment/1");
        assertThat(result.trackingNumber()).isEqualTo("TRACK-1");
    }

    @Test
    @DisplayName("issueRefund calls refundCreate and returns the channel's refund id")
    void issueRefundCallsRefundCreate() {
        RefundResult result = connector.issueRefund(connection(),
                new RefundRequest("gid://shopify/Order/1", null, new BigDecimal("19.99"), "USD"),
                new CallIntentRef(UUID.randomUUID(), "intent-2"));

        assertThat(result.channelRefundId()).isEqualTo("gid://shopify/Refund/1");
    }

    @Test
    @DisplayName("checkCredentials reports invalid when the shop query itself fails")
    void checkCredentialsReportsInvalidToken() {
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("{ shop { name } }"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));

        CredentialStatus status = connector.checkCredentials(connection());

        assertThat(status.valid()).isFalse();
    }

    @Test
    @DisplayName("verifySignature refuses rather than checking against the wrong secret when none is configured")
    void verifySignatureRefusesWithoutWebhookSecret() {
        ChannelConnectionRef noSecret = new ChannelConnectionRef(CONNECTION_ID, ORG_ID,
                ShopifyPlatformConnector.CHANNEL_TYPE,
                "{\"storeDomain\":\"http://localhost:" + wireMock.port() + "\",\"accessToken\":\"test-token\"}");
        byte[] body = "{\"event\":\"order.created\"}".getBytes(StandardCharsets.UTF_8);

        SignatureVerification result = connector.verifySignature(noSecret,
                new RawRequest(body, Map.of("X-Shopify-Hmac-Sha256", "anything")));

        assertThat(result.valid()).isFalse();
        assertThat(result.reason()).contains("no webhook secret");
    }

    // =========================================================================
    // Stub wiring
    // =========================================================================

    private String graphqlPath() {
        return "/admin/api/" + ShopifyPlatformConnector.API_VERSION + "/graphql.json";
    }

    private void registerBaselineStubs() {
        // Orders: exact-boundary window (anExistingOrderEventAt), matched first.
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("orders(first"))
                .withRequestBody(containing("2020-01-01T00:00:00Z"))
                .atPriority(2)
                .willReturn(jsonResponse("""
                        {"data":{"orders":{"edges":[{"cursor":"c-boundary","node":{
                          "id":"gid://shopify/Order/1","name":"#1001","createdAt":"2020-01-01T00:00:00Z",
                          "lineItems":{"edges":[]}
                        }}],"pageInfo":{"hasNextPage":false}}}}
                        """)));

        // Orders: EPOCH-since, first page (after:null) — pagination + declaredCapabilities.
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("orders(first"))
                .withRequestBody(containing("1970-01-01T00:00:00Z"))
                .withRequestBody(containing("\"after\":null"))
                .atPriority(2)
                .willReturn(jsonResponse("""
                        {"data":{"orders":{"edges":[{"cursor":"c-page1","node":{
                          "id":"gid://shopify/Order/A","name":"#A","createdAt":"1970-01-02T00:00:00Z",
                          "lineItems":{"edges":[{"node":{"quantity":1,
                            "discountedUnitPriceSet":{"shopMoney":{"amount":"9.99"}},
                            "variant":{"id":"gid://shopify/ProductVariant/A","sku":"SKU-A","barcode":null}}}]}
                        }}],"pageInfo":{"hasNextPage":true}}}}
                        """)));

        // Orders: EPOCH-since, second page (after:c-page1).
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("orders(first"))
                .withRequestBody(containing("\"after\":\"c-page1\""))
                .atPriority(2)
                .willReturn(jsonResponse("""
                        {"data":{"orders":{"edges":[{"cursor":"c-page2","node":{
                          "id":"gid://shopify/Order/B","name":"#B","createdAt":"1970-01-03T00:00:00Z",
                          "lineItems":{"edges":[]}
                        }}],"pageInfo":{"hasNextPage":false}}}}
                        """)));

        // Orders: catch-all (e.g. the far-future "empty result" window).
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("orders(first"))
                .atPriority(10)
                .willReturn(jsonResponse("""
                        {"data":{"orders":{"edges":[],"pageInfo":{"hasNextPage":false}}}}
                        """)));

        // Catalog: one page, one non-ASCII product — serves both declaredCapabilities and non-ASCII round-trip.
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("productVariants(first"))
                .willReturn(jsonResponse("""
                        {"data":{"productVariants":{"edges":[{"cursor":"c-cat1","node":{
                          "id":"gid://shopify/ProductVariant/NA","sku":"SKU-NA","barcode":"BAR-NA",
                          "product":{"id":"gid://shopify/Product/NA","title":"T\\u00fcrk\\u00e7e \\u00dcr\\u00fcn \\u0130\\u00e7eri\\u011fi \\ud83d\\ude00"},
                          "inventoryItem":{"tracked":true,"inventoryLevels":{"edges":[{"node":{"quantities":[{"quantity":3}]}}]}}
                        }}],"pageInfo":{"hasNextPage":false}}}}
                        """)));

        // Inventory ref resolution for SKU-0/1/2 (partial-failure + correlation tests).
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("nodes(ids"))
                .willReturn(jsonResponse("""
                        {"data":{"nodes":[
                          {"id":"SKU-0","inventoryItem":{"id":"inv-0","inventoryLevels":{"edges":[{"node":{"location":{"id":"loc-1"}}}]}}},
                          {"id":"SKU-1","inventoryItem":{"id":"inv-1","inventoryLevels":{"edges":[{"node":{"location":{"id":"loc-1"}}}]}}},
                          {"id":"SKU-2","inventoryItem":{"id":"inv-2","inventoryLevels":{"edges":[{"node":{"location":{"id":"loc-1"}}}]}}}
                        ]}}
                        """)));

        // inventorySetQuantities: generic success — catches both the plain 3-item happy
        // path and the 2-item retry-without-the-bad-item call (no "inv-1" substring).
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("inventorySetQuantities"))
                .atPriority(10)
                .willReturn(jsonResponse("""
                        {"data":{"inventorySetQuantities":{
                          "inventoryAdjustmentGroup":{"changes":[{"name":"available","delta":1}]},"userErrors":[]}}}
                        """)));

        // Shipment path: order -> open fulfillment order -> fulfillmentCreate.
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("fulfillmentOrders(first"))
                .willReturn(jsonResponse("""
                        {"data":{"order":{"fulfillmentOrders":{"edges":[
                          {"node":{"id":"gid://shopify/FulfillmentOrder/1"}}
                        ]}}}}
                        """)));
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("fulfillmentCreate"))
                .willReturn(jsonResponse("""
                        {"data":{"fulfillmentCreate":{"fulfillment":{
                          "id":"gid://shopify/Fulfillment/1","trackingInfo":[{"number":"TRACK-1"}]},"userErrors":[]}}}
                        """)));

        // Refund path.
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("refundCreate"))
                .willReturn(jsonResponse("""
                        {"data":{"refundCreate":{"refund":{"id":"gid://shopify/Refund/1"},"userErrors":[]}}}
                        """)));

        // checkCredentials' plain shop query (not caught by any of the more specific stubs above).
        wireMock.stubFor(post(urlEqualTo(graphqlPath()))
                .withRequestBody(containing("{ shop { name } }"))
                .atPriority(10)
                .willReturn(jsonResponse("""
                        {"data":{"shop":{"name":"Test Shop"}}}
                        """)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder jsonResponse(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }
}
