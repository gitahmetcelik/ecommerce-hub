package com.ecommercehub.connector;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * plan §8: "Her connector implementasyonunun geçmesi gereken ortak, soyut test
 * sınıfı." Every concrete PlatformConnector's test class extends this and implements
 * the hooks below against whatever backs that connector — MockPlatformConnector's
 * hooks talk to mock-pazaryeri's /_admin endpoints; a real channel's would use
 * WireMock or a sandbox account instead. The test bodies here never change per
 * connector — only how each hook makes the backing channel behave.
 */
public abstract class ConnectorContractTest {

    protected abstract PlatformConnector connector();

    protected abstract ChannelConnectionRef connection();

    /** Must leave the backing channel in a clean, default state. */
    protected abstract void resetScenarios();

    /** The next stock/price bulk update must fail exactly these SKUs, succeed the rest. */
    protected abstract void givenPartialFailureForSkus(Set<String> skus);

    /** The very next call through {@link #connector()} must receive an HTTP 429 from the channel. */
    protected abstract void givenChannelIsRateLimited();

    /** Signs bodyBytes the way the real channel signs an outgoing webhook, for verifySignature to check against. */
    protected abstract String signBody(byte[] bodyBytes);

    /** The name of the signature header verifySignature expects (e.g. "X-Mock-Signature"). */
    protected abstract String signatureHeaderName();

    /** An eventAt timestamp and a title containing non-ASCII characters that the channel actually has an order/product at. */
    protected abstract Instant anExistingOrderEventAt();

    protected abstract String aNonAsciiTitleFromCatalog();

    @Test
    void declaredCapabilitiesAreActuallySupported() {
        Set<Capability> capabilities = connector().capabilities();
        assertThat(capabilities).isNotNull();

        if (capabilities.contains(Capability.FETCH_ORDERS)) {
            assertThat(connector().fetchOrders(connection(), Instant.EPOCH, Page.first(10))).isNotNull();
        }
        if (capabilities.contains(Capability.FETCH_CATALOG)) {
            assertThat(connector().fetchCatalog(connection(), Page.first(10))).isNotNull();
        }
        if (capabilities.contains(Capability.STOCK_PUSH)) {
            assertThat(connector().updateStock(connection(), List.of())).isNotNull();
        }
        if (capabilities.contains(Capability.PRICE_PUSH)) {
            assertThat(connector().updatePrice(connection(), List.of())).isNotNull();
        }
    }

    @Test
    void paginationIsStructurallyConsistent() {
        PagedResult<ChannelOrder> firstPage = connector().fetchOrders(connection(), Instant.EPOCH, new Page(1, 5));

        assertThat(firstPage.items()).hasSizeLessThanOrEqualTo(5);
        assertThat(firstPage.page()).isEqualTo(1);

        if (firstPage.hasMore()) {
            PagedResult<ChannelOrder> secondPage = connector().fetchOrders(connection(), Instant.EPOCH, new Page(2, 5));
            assertThat(secondPage.items()).isNotEmpty();

            Set<String> firstIds = firstPage.items().stream().map(ChannelOrder::channelOrderId).collect(java.util.stream.Collectors.toSet());
            boolean anyOverlap = secondPage.items().stream().anyMatch(o -> firstIds.contains(o.channelOrderId()));
            assertThat(anyOverlap).withFailMessage("Consecutive pages must not repeat the same order").isFalse();
        }
    }

    @Test
    void emptyResultIsAWellFormedEmptyPageNotAnError() {
        PagedResult<ChannelOrder> result = connector().fetchOrders(connection(), Instant.now().plusSeconds(3600 * 24 * 365), Page.first(10));

        assertThat(result.items()).isEmpty();
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void sinceIsInclusiveOfItsExactBoundary() {
        Instant exactly = anExistingOrderEventAt();
        PagedResult<ChannelOrder> result = connector().fetchOrders(connection(), exactly, new Page(1, 100));

        boolean includesBoundary = result.items().stream().anyMatch(o -> !o.eventAt().isBefore(exactly));
        assertThat(includesBoundary)
                .withFailMessage("since must be inclusive (>=) — an exclusive boundary would silently drop the overlap window's whole point")
                .isTrue();
    }

    @Test
    void nonAsciiCharactersSurviveRoundTripIntact() {
        String title = aNonAsciiTitleFromCatalog();
        PagedResult<ChannelProduct> catalog = connector().fetchCatalog(connection(), new Page(1, 100));

        boolean found = catalog.items().stream().anyMatch(p -> title.equals(p.title()));
        assertThat(found)
                .withFailMessage("Non-ASCII title '%s' must come back byte-for-byte identical, not mangled by an encoding mismatch", title)
                .isTrue();
    }

    @Test
    void partialFailureInABulkUpdateIsReportedPerItemNotAsOneBigFailure() {
        resetScenarios();
        givenPartialFailureForSkus(Set.of("SKU-1"));

        List<ItemResult> results = connector().updateStock(connection(),
                List.of(new StockUpdate("SKU-0", 5), new StockUpdate("SKU-1", 5), new StockUpdate("SKU-2", 5)));

        assertThat(results).hasSize(3);
        assertThat(results).filteredOn(r -> r.referenceId().equals("SKU-1")).allSatisfy(r -> assertThat(r.success()).isFalse());
        assertThat(results).filteredOn(r -> !r.referenceId().equals("SKU-1")).allSatisfy(r -> assertThat(r.success()).isTrue());
    }

    @Test
    void rateLimitedCallSurfacesAsADedicatedException() {
        resetScenarios();
        givenChannelIsRateLimited();

        assertThatThrownBy(() -> connector().fetchOrders(connection(), Instant.EPOCH, Page.first(1)))
                .isInstanceOf(ChannelRateLimitedException.class);

        resetScenarios();
    }

    @Test
    void signatureVerificationAcceptsAGenuineSignatureAndRejectsATamperedBody() {
        byte[] body = "{\"event\":\"order.created\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String signature = signBody(body);

        SignatureVerification genuine = connector().verifySignature(connection(),
                new RawRequest(body, java.util.Map.of(signatureHeaderName(), signature)));
        assertThat(genuine.valid()).isTrue();

        byte[] tampered = "{\"event\":\"order.deleted\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        SignatureVerification forged = connector().verifySignature(connection(),
                new RawRequest(tampered, java.util.Map.of(signatureHeaderName(), signature)));
        assertThat(forged.valid()).isFalse();
    }

    @Test
    void repeatingTheSameIntentForAShipmentProducesExactlyOneShipment() {
        resetScenarios();
        CallIntentRef intent = new CallIntentRef(UUID.randomUUID(), UUID.randomUUID().toString());
        ShipmentRequest request = new ShipmentRequest("order-0");

        ShipmentResult first = connector().createShipment(connection(), request, intent);
        ShipmentResult second = connector().createShipment(connection(), request, intent);

        assertThat(second.trackingNumber())
                .withFailMessage("plan §8: repeating a call with the same intent must produce one channel-side effect, not two")
                .isEqualTo(first.trackingNumber());
    }
}
