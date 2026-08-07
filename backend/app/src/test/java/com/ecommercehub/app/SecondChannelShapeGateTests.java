package com.ecommercehub.app;

import com.ecommercehub.app.push.ChannelPushSender;
import com.ecommercehub.app.reconcile.ReconcileService;
import com.ecommercehub.connector.CallIntentRef;
import com.ecommercehub.connector.CallStatus;
import com.ecommercehub.connector.Capability;
import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.connector.ChannelOrder;
import com.ecommercehub.connector.ChannelOrderItem;
import com.ecommercehub.connector.Page;
import com.ecommercehub.connector.PagedResult;
import com.ecommercehub.connector.PlatformConnector;
import com.ecommercehub.connector.ShipmentRequest;
import com.ecommercehub.connector.ShipmentResult;
import com.ecommercehub.connector.mock.MockBarcodeMarketplaceConnector;
import com.ecommercehub.domain.catalog.CatalogMatchingService;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.security.EncryptedCredential;
import com.ecommercehub.domain.stock.StockLedgerService;
import com.ecommercehub.domain.tenant.TenantContextService;
import com.ecommercehub.ingest.ConnectorRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A second channel shape, not a second copy of the first.
 *
 * <p>What a real second integration confronts a hub with is rarely another API to call —
 * it is a different <em>shape</em>: identifiers that are not ours, capabilities that are
 * absent, and guarantees that do not hold. Plan §8's capability matrix is the whole hedge
 * against that, and a matrix exercised by only one profile is a matrix in name only.
 *
 * <p>The channel here is barcode-keyed with no seller SKU, pushes no webhooks, and does
 * not honour a client idempotency key. Each of those is made genuinely true in the mock
 * rather than merely declared, because a fixture that is safe to retry cannot prove the
 * hub never retries.
 */
@SpringBootTest
public class SecondChannelShapeGateTests extends AbstractTestcontainersTest {

    private static final int PORT = 4100;
    private static final Path MOCK_PAZARYERI_DIR = Paths.get("../../mock-pazaryeri").toAbsolutePath().normalize();

    private static final GenericContainer<?> mockPazaryeri =
            new GenericContainer<>(new ImageFromDockerfile().withFileFromPath(".", MOCK_PAZARYERI_DIR))
                    .withExposedPorts(PORT);

    static {
        mockPazaryeri.start();
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TenantContextService tenantContextService;
    @Autowired private CatalogMatchingService catalogMatchingService;
    @Autowired private ReconcileService reconcileService;
    @Autowired private ConnectorRegistry connectorRegistry;
    @Autowired private CredentialEncryptionService credentialEncryptionService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private StockLedgerService stockLedgerService;
    @Autowired private ChannelPushSender pushSender;

    private UUID orgId;
    private UUID barcodeChannelId;
    private String mockUrl;

    @BeforeEach
    void setUp() {
        mockUrl = "http://" + mockPazaryeri.getHost() + ":" + mockPazaryeri.getMappedPort(PORT);
        post(mockUrl + "/_admin/reset", "{}");

        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");

        EncryptedCredential encrypted = credentialEncryptionService.encrypt(mockUrl);
        barcodeChannelId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection (id, organization_id, channel_type, encrypted_credentials, key_version)
                VALUES (?, ?, ?, ?, ?)
                """, barcodeChannelId, orgId, MockBarcodeMarketplaceConnector.CHANNEL_TYPE,
                encrypted.ciphertextBase64(), encrypted.keyVersion());
    }

    // =========================================================================
    // Identity: this channel has no SKU at all
    // =========================================================================

    @Test
    @DisplayName("Second shape: a channel with no seller SKU still imports — the hub mints its own identifier")
    void catalogWithoutSkuStillImports() {
        UUID variantId = importFromBarcodeChannel("EAN-900001", "Marketplace2 Product 1");

        var variant = jdbcTemplate.queryForMap(
                "SELECT sku, barcode FROM hub.variant WHERE id = ?", variantId);

        assertThat(variant.get("barcode")).isEqualTo("EAN-900001");
        assertThat(variant.get("sku"))
                .withFailMessage("Plan §3 makes the SKU ours, not the channel's — a channel without one is a "
                        + "channel we have to name things for, and the column is NOT NULL for that reason")
                .isEqualTo("BC-EAN-900001");
    }

    @Test
    @DisplayName("Second shape: re-importing the same catalogue converges instead of multiplying variants")
    void reimportIsStable() {
        UUID first = importFromBarcodeChannel("EAN-900002", "Marketplace2 Product 2");
        UUID second = importFromBarcodeChannel("EAN-900002", "Marketplace2 Product 2");

        assertThat(second)
                .withFailMessage("A minted identifier that is not deterministic turns every re-import into a "
                        + "fresh catalogue")
                .isEqualTo(first);

        Integer variants = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.variant WHERE organization_id = ?", Integer.class, orgId);
        assertThat(variants).isEqualTo(1);
    }

    @Test
    @DisplayName("Second shape: the same physical product sold on two channels resolves to ONE variant")
    void sameProductOnTwoChannelsSharesOneVariant() {
        // Already in the catalogue under our own SKU, carrying the barcode the other
        // channel knows it by.
        UUID existingVariantId = seedVariantWithBarcode("SHIRT-BLUE-M", "EAN-900003");

        UUID imported = importFromBarcodeChannel("EAN-900003", "Marketplace2 Product 3");

        assertThat(imported)
                .withFailMessage("Two variants for one physical product means two independent ideas of its stock, "
                        + "and the oversell that follows is arithmetic rather than bad luck")
                .isEqualTo(existingVariantId);

        String source = jdbcTemplate.queryForObject("""
                SELECT mapping_source FROM hub.channel_product_mapping
                WHERE organization_id = ? AND channel_connection_id = ?
                """, String.class, orgId, barcodeChannelId);
        assertThat(source).isEqualTo("AUTO_BARCODE");
    }

    // =========================================================================
    // Capabilities: absent ones change behaviour, they are not labels
    // =========================================================================

    @Test
    @DisplayName("Second shape: with no WEBHOOK capability, orders can only arrive by polling — and they do")
    void ordersArriveThroughPollingOnly() {
        PlatformConnector connector = connectorRegistry.require(MockBarcodeMarketplaceConnector.CHANNEL_TYPE);

        assertThat(connector.capabilities())
                .withFailMessage("This shape exists precisely to exercise the polling-first path")
                .doesNotContain(Capability.WEBHOOK);

        assertThatThrownBy(() -> connector.verifySignature(connectionRef(), null))
                .withFailMessage("A channel that sends no webhooks must not pretend it can verify one")
                .isInstanceOf(UnsupportedOperationException.class);

        // Nothing has been pushed and nothing could be. The reconcile poll is the only route.
        int observed = reconcileService.reconcileOpenOrders(orgId, barcodeChannelId);

        assertThat(observed)
                .withFailMessage("mock-pazaryeri seeds eight orders on this shape; the poll is what has to find them")
                .isEqualTo(8);

        Integer orders = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.sales_order WHERE organization_id = ?", Integer.class, orgId);
        assertThat(orders).isEqualTo(8);
    }

    @Test
    @DisplayName("Second shape: this channel genuinely duplicates on retry, so a retry is never the recovery")
    void retryIsUnsafeSoStatusQueryIsTheRecovery() {
        PlatformConnector connector = connectorRegistry.require(MockBarcodeMarketplaceConnector.CHANNEL_TYPE);

        assertThat(connector.capabilities()).doesNotContain(Capability.REQUEST_IDEMPOTENCY_KEY);

        UUID intentId = UUID.randomUUID();
        CallIntentRef intent = new CallIntentRef(intentId, intentId.toString());

        ShipmentResult first = connector.createShipment(connectionRef(), new ShipmentRequest("m2-order-0"), intent);
        assertThat(first.channelShipmentId()).isNotBlank();

        // The same intent again. On the first marketplace this returns the original
        // shipment; here it creates a second label — which is exactly why the hub must
        // never take this path.
        connector.createShipment(connectionRef(), new ShipmentRequest("m2-order-0"), intent);

        assertThat(labelCountAtChannel())
                .withFailMessage("The fixture has to be genuinely unsafe, otherwise 'the hub never retries' is "
                        + "a claim nothing can falsify")
                .isEqualTo(2);

        // The compensation the plan mandates when the capability is missing: ask, do not repeat.
        CallStatus status = connector.queryCallStatus(connectionRef(), intent);
        assertThat(status.resolved())
                .withFailMessage("Without an idempotency key, a status query is the only way an in-flight call "
                        + "can be resolved without paying for it twice")
                .isTrue();
    }

    @Test
    @DisplayName("Second shape: capabilities it lacks fail loudly rather than answering plausibly")
    void missingCapabilitiesRefuseRatherThanReturnEmpty() {
        PlatformConnector connector = connectorRegistry.require(MockBarcodeMarketplaceConnector.CHANNEL_TYPE);

        // An empty page of returns is indistinguishable from a channel that has none, and
        // the bug that follows is a business process that quietly never runs.
        assertThatThrownBy(() -> connector.fetchReturns(connectionRef(), java.time.Instant.EPOCH,
                new com.ecommercehub.connector.Page(1, 10)))
                .isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> connector.issueRefund(connectionRef(), null, null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // =========================================================================
    // Plan v5 §1.7: the channel's own identifier goes out on the wire, not our sku
    // =========================================================================

    @Test
    @DisplayName("v5 Faz 1 gate 1/2: a barcode-keyed channel's stock push carries the barcode on /v2, never the v1 route")
    void barcodeChannelPushCarriesChannelIdentifierOnTheV2Route() {
        UUID variantId = seedVariantWithBarcode("SHOE-1", "EAN-900004");
        mapToBarcodeChannel(variantId, "EAN-900004");

        inTenant(() -> stockLedgerService.recordSupply(orgId, variantId, 14, null));

        int confirmed = pushSender.sendWindow(orgId, barcodeChannelId);

        assertThat(confirmed).isEqualTo(1);
        assertThat(channelPushStatusOf(variantId)).isEqualTo("SENT");
        assertThat(callCountFor("/v2/stock/bulk-update"))
                .withFailMessage("The barcode-keyed connector must call the v2 route with the channel's own identifier")
                .isEqualTo(1);
        assertThat(callCountFor("/stock/bulk-update"))
                .withFailMessage("H1: sending to the v1 (sku-keyed) route means the barcode channel's stock never "
                        + "actually updates — this is the bug Plan v5 Faz 1 closes")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("v5 Faz 1 gate 3: a channel that keeps rejecting an unrecognised identifier goes to the operator "
            + "queue instead of retrying forever")
    void unrecognisedIdentifierEscalatesToOperatorQueueInsteadOfLoopingForever() {
        UUID variantId = seedVariantWithBarcode("SHOE-2", "EAN-LOCAL-ONLY");
        // A mapping the marketplace itself has never heard of — the real-world shape of
        // a stale or hand-entered mapping pointing at an identifier that does not exist.
        mapToBarcodeChannel(variantId, "EAN-DOES-NOT-EXIST-ON-CHANNEL");

        inTenant(() -> stockLedgerService.recordSupply(orgId, variantId, 5, null));

        for (int attempt = 1; attempt <= 3; attempt++) {
            int confirmed = pushSender.sendWindow(orgId, barcodeChannelId);
            assertThat(confirmed).isZero();
        }

        assertThat(channelPushStatusOf(variantId))
                .withFailMessage("After the failure threshold, the row must leave PENDING — otherwise every future "
                        + "window keeps retrying an identifier the channel will never recognise")
                .isEqualTo("STUCK");
        assertThat(consecutiveFailuresOf(variantId)).isEqualTo(3);

        // Off the retry train now — one more window must claim nothing for this row.
        assertThat(pushSender.sendWindow(orgId, barcodeChannelId)).isZero();

        UUID pushRowId = channelPushRowId(variantId);
        Integer stuckQueueRows = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM hub.operator_queue
                WHERE organization_id = ? AND type = 'CHANNEL_PUSH_STUCK' AND reference_id = ? AND status = 'PENDING'
                """, Integer.class, orgId, pushRowId);
        assertThat(stuckQueueRows)
                .withFailMessage("A row stuck on a bad identifier must surface to an operator, not just stop silently")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("v5 Faz 1 gate 5: an order line from a barcode-keyed channel carries no sku — the barcode is what identifies it")
    void barcodeChannelOrderItemsCarryNoSku() {
        PlatformConnector connector = connectorRegistry.require(MockBarcodeMarketplaceConnector.CHANNEL_TYPE);

        PagedResult<ChannelOrder> orders = connector.fetchOrders(connectionRef(), java.time.Instant.EPOCH, Page.first(10));

        assertThat(orders.items()).isNotEmpty();
        for (ChannelOrder order : orders.items()) {
            for (ChannelOrderItem item : order.items()) {
                assertThat(item.item().sku())
                        .withFailMessage("Plan v5 §1: this channel has no seller sku — the field must not be made "
                                + "to lie about having one")
                        .isNull();
                assertThat(item.item().barcode()).isNotBlank();
                assertThat(item.item().channelVariantId()).isEqualTo(item.item().barcode());
            }
        }

        // And the order line still binds to the right variant end-to-end via reconcile.
        UUID variantId = seedVariantWithBarcode("SHOE-3", "EAN-900005");
        mapToBarcodeChannel(variantId, "EAN-900005");
        reconcileService.reconcileOpenOrders(orgId, barcodeChannelId);

        Integer boundItems = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.order_item WHERE variant_id = ?", Integer.class, variantId);
        assertThat(boundItems)
                .withFailMessage("An order line for a mapped barcode must bind to that variant, not fall through unmatched")
                .isGreaterThan(0);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private UUID importFromBarcodeChannel(String barcode, String title) {
        return inTenant(() -> catalogMatchingService.importFromChannel(
                orgId, barcodeChannelId, barcode, barcode, null, barcode, title));
    }

    private void mapToBarcodeChannel(UUID variantId, String channelVariantId) {
        jdbcTemplate.update("""
                INSERT INTO hub.channel_product_mapping
                    (id, organization_id, variant_id, channel_connection_id, channel_product_id, channel_variant_id, mapping_source)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 'MANUAL')
                """, orgId, variantId, barcodeChannelId, channelVariantId, channelVariantId);
    }

    private String channelPushStatusOf(UUID variantId) {
        return jdbcTemplate.queryForObject("""
                SELECT status FROM hub.channel_push WHERE channel_connection_id = ? AND variant_id = ? AND type = 'STOCK'
                """, String.class, barcodeChannelId, variantId);
    }

    private int consecutiveFailuresOf(UUID variantId) {
        return jdbcTemplate.queryForObject("""
                SELECT consecutive_failures FROM hub.channel_push
                WHERE channel_connection_id = ? AND variant_id = ? AND type = 'STOCK'
                """, Integer.class, barcodeChannelId, variantId);
    }

    private UUID channelPushRowId(UUID variantId) {
        return jdbcTemplate.queryForObject("""
                SELECT id FROM hub.channel_push WHERE channel_connection_id = ? AND variant_id = ? AND type = 'STOCK'
                """, UUID.class, barcodeChannelId, variantId);
    }

    private int callCountFor(String path) {
        JsonNode counts = readJson(get(mockUrl + "/_admin/stats")).get("callCountsByPath");
        JsonNode count = counts.get(path);
        return count == null ? 0 : count.asInt();
    }

    private UUID seedVariantWithBarcode(String sku, String barcode) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.product (id, organization_id, title) VALUES (?, ?, ?)",
                productId, orgId, "Product " + sku);
        jdbcTemplate.update("""
                INSERT INTO hub.variant (id, organization_id, product_id, sku, barcode) VALUES (?, ?, ?, ?, ?)
                """, variantId, orgId, productId, sku, barcode);
        return variantId;
    }

    private ChannelConnectionRef connectionRef() {
        return new ChannelConnectionRef(barcodeChannelId, orgId,
                MockBarcodeMarketplaceConnector.CHANNEL_TYPE, mockUrl);
    }

    private int labelCountAtChannel() {
        JsonNode shipments = readJson(get(mockUrl + "/_admin/v2/shipments")).get("shipments");
        return shipments.size();
    }

    private <T> T inTenant(java.util.function.Supplier<T> work) {
        return transactionTemplate.execute(status -> {
            tenantContextService.setTransactionTenantContext(orgId);
            return work.get();
        });
    }

    private void inTenant(Runnable work) {
        transactionTemplate.executeWithoutResult(status -> {
            tenantContextService.setTransactionTenantContext(orgId);
            work.run();
        });
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unparseable mock-pazaryeri response: " + body, e);
        }
    }

    private String get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET().build());
    }

    private String post(String url, String body) {
        return send(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("mock-pazaryeri " + request.uri() + " returned "
                        + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("mock-pazaryeri call failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
