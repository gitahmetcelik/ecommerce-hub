package com.ecommercehub.app;

import com.ecommercehub.app.push.ChannelPushSender;
import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.price.PriceService;
import com.ecommercehub.domain.push.ChannelPushService;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.security.EncryptedCredential;
import com.ecommercehub.domain.tenant.TenantContextService;
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

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan v5 §6.4 gate: the center is authoritative for price, a channel-specific override
 * wins over the list price until cleared, changes coalesce exactly like stock, a stock
 * window and a price window pending at once do not collide on one task key, a channel
 * with no PRICE_PUSH capability never accumulates a row it cannot send, and OBSERVER
 * cannot change price at all.
 *
 * <p>Runs against a real mock-pazaryeri over HTTP, same reasoning as {@code
 * Faz4GateTests}: the coalescing claim is about how many network calls actually
 * happened, which an in-process fake could not honestly answer.
 */
@SpringBootTest
public class PriceGateTests extends AbstractTestcontainersTest {

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
    @Autowired private PriceService priceService;
    @Autowired private ChannelPushSender pushSender;
    @Autowired private com.ecommercehub.app.push.PushWindowScheduler windowScheduler;
    @Autowired private CredentialEncryptionService credentialEncryptionService;
    @Autowired private ObjectMapper objectMapper;

    private UUID orgId;
    private UUID connectionA;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://" + mockPazaryeri.getHost() + ":" + mockPazaryeri.getMappedPort(PORT);
        adminPost("/_admin/reset", "{}");

        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");

        connectionA = insertConnection("MOCK");
    }

    private AuthenticatedUser actor(HubRole role) {
        return new AuthenticatedUser(UUID.randomUUID(), orgId, "actor@test", List.of(role));
    }

    // =========================================================================
    // Gate 1 — a list price change fans out to every channel selling the variant
    // =========================================================================

    @Test
    @DisplayName("Faz 6 gate: setting the list price queues a price push for every channel that sells the variant")
    void listPriceChangeQueuesPushForEveryMappedChannel() {
        UUID variantId = insertVariant("SKU-LIST");
        mapToChannel(connectionA, variantId, "SKU-LIST");

        inTenant(() -> priceService.setListPrice(actor(HubRole.OPERATOR), variantId,
                new BigDecimal("19.99"), "USD", new BigDecimal("20.00")));

        assertThat(pushRowCount(connectionA, ChannelPushService.TYPE_PRICE)).isEqualTo(1);

        int confirmed = pushSender.sendWindow(orgId, connectionA, ChannelPushService.TYPE_PRICE);
        assertThat(confirmed).isEqualTo(1);
        assertThat(channelPrice("SKU-LIST")).isEqualByComparingTo("19.99");
    }

    // =========================================================================
    // Gate 2 — a channel override wins; clearing it reverts to the list price
    // =========================================================================

    @Test
    @DisplayName("Faz 6 gate: a channel price overrides the list price, and clearing it reverts to the list price")
    void channelPriceOverridesListPriceUntilCleared() {
        UUID variantId = insertVariant("SKU-OVERRIDE");
        mapToChannel(connectionA, variantId, "SKU-OVERRIDE");

        inTenant(() -> priceService.setListPrice(actor(HubRole.OPERATOR), variantId,
                new BigDecimal("10.00"), "USD", new BigDecimal("20.00")));
        pushSender.sendWindow(orgId, connectionA, ChannelPushService.TYPE_PRICE);
        assertThat(channelPrice("SKU-OVERRIDE")).isEqualByComparingTo("10.00");

        inTenant(() -> priceService.setChannelPrice(actor(HubRole.OPERATOR), connectionA, variantId,
                new BigDecimal("7.50"), null));
        pushSender.sendWindow(orgId, connectionA, ChannelPushService.TYPE_PRICE);
        assertThat(channelPrice("SKU-OVERRIDE"))
                .withFailMessage("A channel-specific price must win over the list price")
                .isEqualByComparingTo("7.50");

        inTenant(() -> priceService.clearChannelPrice(actor(HubRole.OPERATOR), connectionA, variantId));
        pushSender.sendWindow(orgId, connectionA, ChannelPushService.TYPE_PRICE);
        assertThat(channelPrice("SKU-OVERRIDE"))
                .withFailMessage("Deleting the channel override must revert the channel to the list price")
                .isEqualByComparingTo("10.00");
    }

    // =========================================================================
    // Gate 3 — coalescing: many changes to one variant collapse to one call
    // =========================================================================

    @Test
    @DisplayName("Faz 6 gate: 20 price changes to one variant collapse into a single channel call carrying the last value")
    void twentyChangesBecomeOneCallWithTheLastValue() {
        UUID variantId = insertVariant("SKU-PRICE-COALESCE");
        mapToChannel(connectionA, variantId, "SKU-PRICE-COALESCE");

        inTenant(() -> {
            for (int i = 1; i <= 20; i++) {
                priceService.setListPrice(actor(HubRole.OPERATOR), variantId,
                        new BigDecimal(i).setScale(2), "USD", new BigDecimal("20.00"));
            }
        });

        assertThat(pushRowCount(connectionA, ChannelPushService.TYPE_PRICE))
                .withFailMessage("20 changes to one variant must coalesce onto one channel_push row, not append 20")
                .isEqualTo(1);

        int confirmed = pushSender.sendWindow(orgId, connectionA, ChannelPushService.TYPE_PRICE);

        assertThat(confirmed).isEqualTo(1);
        assertThat(priceUpdateCallCount())
                .withFailMessage("One window over 20 coalesced changes must produce exactly one bulk call")
                .isEqualTo(1);
        assertThat(channelPrice("SKU-PRICE-COALESCE"))
                .withFailMessage("The channel must end up holding the LAST value, not an intermediate one")
                .isEqualByComparingTo("20.00");
    }

    // =========================================================================
    // Gate 4 — a stock window and a price window pending at once do not collide
    // =========================================================================

    @Test
    @DisplayName("Faz 6 gate: a stock push and a price push pending in the same window get two separate tasks, both of which run")
    void stockAndPricePendingInSameWindowGetSeparateTasks() {
        UUID variantId = insertVariant("SKU-BOTH");
        mapToChannel(connectionA, variantId, "SKU-BOTH");

        inTenant(() -> priceService.setListPrice(actor(HubRole.OPERATOR), variantId,
                new BigDecimal("5.00"), "USD", new BigDecimal("20.00")));
        // A stock row for the same connection/variant, independent of the ledger — the
        // point here is two TYPES pending at once, not stock semantics.
        inTenant(() -> jdbcTemplate.update("""
                INSERT INTO hub.channel_push (id, organization_id, channel_connection_id, variant_id, type, target_value, status)
                VALUES (gen_random_uuid(), ?, ?, ?, 'STOCK',
                        CAST('{"channelVariantId":"SKU-BOTH","sku":"SKU-BOTH","barcode":null,"quantity":3}' AS jsonb), 'PENDING')
                """, orgId, connectionA, variantId));

        java.time.Instant window = java.time.Instant.ofEpochMilli(1_800_000_000_000L);
        windowScheduler.openWindows(window);

        List<String> taskKeys = jdbcTemplate.queryForList("""
                SELECT task_key FROM hub.work_batch
                WHERE organization_id = ? AND task_type = 'push-send' ORDER BY task_key
                """, String.class, orgId);
        assertThat(taskKeys)
                .withFailMessage("A key without the push type would let one type silently swallow the other (v4 §4.2)")
                .containsExactlyInAnyOrder(connectionA + ":PRICE:" + window, connectionA + ":STOCK:" + window);

        assertThat(pushSender.sendWindow(orgId, connectionA, ChannelPushService.TYPE_PRICE)).isEqualTo(1);
        assertThat(pushSender.sendWindow(orgId, connectionA, ChannelPushService.TYPE_STOCK)).isEqualTo(1);
    }

    // =========================================================================
    // Gate 5 — a channel with no PRICE_PUSH capability never gets a row
    // =========================================================================

    @Test
    @DisplayName("Faz 6 gate: a channel whose connector has no PRICE_PUSH capability never accumulates a price push row")
    void noPricePushCapabilityMeansNoRowAtAll() {
        UUID noPriceConnection = insertConnection("MOCK_NO_PRICE_PUSH");
        UUID variantId = insertVariant("SKU-NO-PRICE-PUSH");
        mapToChannel(noPriceConnection, variantId, "SKU-NO-PRICE-PUSH");

        inTenant(() -> priceService.setListPrice(actor(HubRole.OPERATOR), variantId,
                new BigDecimal("12.00"), "USD", new BigDecimal("20.00")));

        assertThat(pushRowCount(noPriceConnection, ChannelPushService.TYPE_PRICE))
                .withFailMessage("Nothing would ever consume a price push row for a connector with no PRICE_PUSH — "
                        + "it must never be created")
                .isZero();
    }

    // =========================================================================
    // Gate 6 — OBSERVER cannot change price
    // =========================================================================

    @Test
    @DisplayName("Faz 6 gate: OBSERVER cannot set a price; the attempt is audited as PERMISSION_DENIED")
    void observerCannotSetPrice() {
        UUID variantId = insertVariant("SKU-DENY");

        assertThatThrownBy(() -> priceService.setListPrice(actor(HubRole.OBSERVER), variantId,
                new BigDecimal("1.00"), "USD", new BigDecimal("20.00")))
                .isInstanceOf(InsufficientRoleException.class);

        assertThat(pushRowCount(connectionA, ChannelPushService.TYPE_PRICE)).isZero();

        List<String> actions = jdbcTemplate.queryForList("""
                SELECT action FROM hub.audit_log WHERE organization_id = ? AND action = 'PERMISSION_DENIED'
                """, String.class, orgId);
        assertThat(actions).isNotEmpty();
    }

    // =========================================================================
    // Fixtures
    // =========================================================================

    private UUID insertConnection(String channelType) {
        EncryptedCredential encrypted = credentialEncryptionService.encrypt(baseUrl);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection (id, organization_id, channel_type, encrypted_credentials, key_version)
                VALUES (?, ?, ?, ?, ?)
                """, id, orgId, channelType, encrypted.ciphertextBase64(), encrypted.keyVersion());
        return id;
    }

    private UUID insertVariant(String sku) {
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.product (id, organization_id, title) VALUES (?, ?, ?)",
                productId, orgId, "Product " + sku);
        UUID variantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.variant (id, organization_id, product_id, sku) VALUES (?, ?, ?, ?)",
                variantId, orgId, productId, sku);
        return variantId;
    }

    private void mapToChannel(UUID connectionId, UUID variantId, String channelVariantId) {
        jdbcTemplate.update("""
                INSERT INTO hub.channel_product_mapping
                    (id, organization_id, variant_id, channel_connection_id, channel_product_id, channel_variant_id, mapping_source)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 'MANUAL')
                """, orgId, variantId, connectionId, channelVariantId, channelVariantId);
    }

    private int pushRowCount(UUID connectionId, String type) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM hub.channel_push WHERE channel_connection_id = ? AND type = ?
                """, Integer.class, connectionId, type);
        return count == null ? 0 : count;
    }

    /**
     * A fresh cross-transaction read of {@code hub.price.list_price} (NUMERIC(19,4))
     * comes back at scale 4 even when it was written at scale 2 — the same value, a
     * different formatting. Returning BigDecimal (compared with isEqualByComparingTo)
     * keeps assertions honest about VALUE without coupling to incidental scale.
     */
    private BigDecimal channelPrice(String sku) {
        JsonNode price = readJson(adminGet("/price")).get("priceBySku").get(sku);
        return price == null || price.isNull() ? null : new BigDecimal(price.asText());
    }

    private int priceUpdateCallCount() {
        JsonNode counts = readJson(adminGet("/_admin/stats")).get("callCountsByPath");
        JsonNode calls = counts.get("/price/bulk-update");
        return calls == null ? 0 : calls.asInt();
    }

    private void inTenant(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> {
            tenantContextService.setTransactionTenantContext(orgId);
            action.run();
        });
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unparseable mock-pazaryeri response: " + body, e);
        }
    }

    private String adminGet(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build());
    }

    private String adminPost(String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build());
    }

    private String send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString()).body();
        } catch (Exception e) {
            throw new IllegalStateException("mock-pazaryeri request failed", e);
        }
    }
}
