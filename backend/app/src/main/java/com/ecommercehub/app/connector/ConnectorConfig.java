package com.ecommercehub.app.connector;

import com.ecommercehub.connector.Capability;
import com.ecommercehub.connector.PlatformConnector;
import com.ecommercehub.connector.mock.MockBarcodeMarketplaceConnector;
import com.ecommercehub.connector.mock.MockPlatformConnector;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.util.EnumSet;
import java.util.Set;

/** Registers every available PlatformConnector — only Mock exists so far (Phase 1/§14 decision). */
@Configuration
public class ConnectorConfig {

    /** The common shape: the marketplace is the merchant of record and refunds customers itself. */
    @Bean
    public PlatformConnector mockPlatformConnector(ObjectMapper objectMapper) {
        return new MockPlatformConnector(HttpClient.newHttpClient(), objectMapper);
    }

    /**
     * The other side of Plan §7's capability branch: a channel where <em>we</em> issue the
     * refund. Registered as a distinct channel type so both branches of the return flow
     * exist in one running application — with only the default profile, the REFUND_BY_US
     * path would be code that never executes anywhere, which is how it stays broken.
     */
    @Bean
    public PlatformConnector mockRefundingPlatformConnector(ObjectMapper objectMapper) {
        Set<Capability> capabilities = EnumSet.of(
                Capability.FETCH_ORDERS, Capability.FETCH_CATALOG, Capability.STOCK_PUSH, Capability.PRICE_PUSH,
                Capability.RETURN_DECISION_SUBMIT, Capability.SHIPMENT_CREATE, Capability.REFUND_BY_US,
                Capability.WEBHOOK, Capability.REQUEST_IDEMPOTENCY_KEY);

        return new MockPlatformConnector(HttpClient.newHttpClient(), objectMapper, "MOCK_REFUND", capabilities);
    }

    /**
     * A genuinely different channel shape: barcode-keyed, no webhooks, no client
     * idempotency key. Registered so the hub runs against more than one shape at once —
     * a capability matrix only exercised by a single profile is a matrix in name only.
     */
    @Bean
    public PlatformConnector mockBarcodeMarketplaceConnector(ObjectMapper objectMapper) {
        return new MockBarcodeMarketplaceConnector(HttpClient.newHttpClient(), objectMapper);
    }
}
