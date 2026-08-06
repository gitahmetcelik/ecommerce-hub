package com.ecommercehub.app;

import com.ecommercehub.app.push.ChannelPushSender;
import com.ecommercehub.app.reconcile.ReconcileService;
import com.ecommercehub.domain.order.OrderEventPayload;
import com.ecommercehub.domain.order.OrderItemStatus;
import com.ecommercehub.domain.order.OrderProcessingService;
import com.ecommercehub.domain.push.ChannelPushStore;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.security.EncryptedCredential;
import com.ecommercehub.domain.stock.StockConsistencyService;
import com.ecommercehub.domain.stock.StockLedgerService;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan §12 Phase 4 gate: push coalescing, the per-channel buffer and last-unit allocation,
 * oversell detection, the reconcile layers, and the channel circuit breaker.
 *
 * <p>Everything runs against a real Postgres and a real mock-pazaryeri over HTTP — the
 * coalescing claims are about how many network calls actually happen, which an
 * in-process fake could not honestly answer.
 */
// Background sweeps are off for all tests (see src/test/resources/application.properties).
// The low failure threshold is this suite's own: gate 7b has to trip the breaker, and
// doing that against the production default of 5 would just be five identical calls.
@SpringBootTest(properties = "hub.channel.circuit-failure-threshold=2")
public class Faz4GateTests extends AbstractTestcontainersTest {

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
    @Autowired private StockLedgerService stockLedgerService;
    @Autowired private StockConsistencyService stockConsistencyService;
    @Autowired private ChannelPushSender pushSender;
    @Autowired private ChannelPushStore pushStore;
    @Autowired private com.ecommercehub.app.push.PushWindowScheduler windowScheduler;
    @Autowired private ReconcileService reconcileService;
    @Autowired private OrderProcessingService orderProcessingService;
    @Autowired private CredentialEncryptionService credentialEncryptionService;
    @Autowired private ObjectMapper objectMapper;

    private UUID orgId;
    private UUID connectionA;
    private UUID connectionB;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://" + mockPazaryeri.getHost() + ":" + mockPazaryeri.getMappedPort(PORT);
        adminPost("/_admin/reset", "{}");

        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name, low_stock_threshold) VALUES (?, ?, 1)", orgId, "Org");

        // B outranks A, so the last-unit allocation winner is a stated policy, not a
        // coin flip decided by whichever row the planner happened to return first.
        connectionA = insertConnection(0);
        connectionB = insertConnection(10);
    }

    // =========================================================================
    // Gate 1 — coalescing
    // =========================================================================

    @Test
    @DisplayName("Phase 4 gate 1: 50 stock changes to one variant collapse into a single channel call carrying the last value")
    void test1_FiftyChangesBecomeOneCall() {
        UUID variantId = insertVariant("SKU-COALESCE", 1000);
        mapToChannel(connectionA, variantId, "SKU-COALESCE");

        inTenant(() -> {
            for (int i = 0; i < 50; i++) {
                stockLedgerService.recordSupply(orgId, variantId, 1, null);
            }
        });

        assertThat(pushRowCount(connectionA))
                .withFailMessage("50 changes to one variant must coalesce onto one channel_push row, not append 50")
                .isEqualTo(1);
        assertThat(generationOf(connectionA, variantId))
                .withFailMessage("Every change must still advance the generation — that is what invalidates in-flight sends")
                .isGreaterThanOrEqualTo(50L);

        int confirmed = pushSender.sendWindow(orgId, connectionA);

        assertThat(confirmed).isEqualTo(1);
        assertThat(stockUpdateCallCount())
                .withFailMessage("One window over 50 coalesced changes must produce exactly one bulk call")
                .isEqualTo(1);
        assertThat(channelStock("SKU-COALESCE"))
                .withFailMessage("The channel must end up holding the LAST value, not an intermediate one")
                .isEqualTo(1050);
    }

    // =========================================================================
    // Gate 2 — a value that changes while the send is in flight must not be lost
    // =========================================================================

    @Test
    @DisplayName("Phase 4 gate 2: a value arriving mid-flight leaves the row PENDING and gets sent, instead of being overwritten by the stale success")
    void test2_MidFlightChangeIsNotLost() throws Exception {
        UUID variantId = insertVariant("SKU-INFLIGHT", 10);
        mapToChannel(connectionA, variantId, "SKU-INFLIGHT");
        inTenant(() -> stockLedgerService.recordSupply(orgId, variantId, 0, null));
        enqueueCurrentAvailability(variantId);

        // Slow the channel down so there is a real window in which to change the value.
        adminPost("/_admin/scenario", "{\"delayMsByPath\": {\"/stock/bulk-update\": 2000}}");

        CompletableFuture<Integer> inFlight =
                CompletableFuture.supplyAsync(() -> pushSender.sendWindow(orgId, connectionA));

        // Wait for the claim to be committed — that is what makes this a genuine
        // concurrent writer rather than a lock wait behind the sender's own transaction.
        awaitStatus(connectionA, variantId, "SENDING");
        long claimedGeneration = generationOf(connectionA, variantId);

        inTenant(() -> stockLedgerService.recordSupply(orgId, variantId, 5, null));

        int confirmed = inFlight.get();

        assertThat(confirmed)
                .withFailMessage("The CAS must reject a success for a generation that has already been superseded")
                .isZero();
        assertThat(statusOf(connectionA, variantId))
                .withFailMessage("A superseded row must stay PENDING so the newer value still goes out")
                .isEqualTo("PENDING");
        assertThat(generationOf(connectionA, variantId)).isGreaterThan(claimedGeneration);

        adminPost("/_admin/scenario", "{\"delayMsByPath\": {}}");
        pushSender.sendWindow(orgId, connectionA);

        assertThat(channelStock("SKU-INFLIGHT"))
                .withFailMessage("The value that arrived mid-flight must reach the channel, not be lost with the stale send")
                .isEqualTo(15);
    }

    // =========================================================================
    // Gate 3 — oversell
    // =========================================================================

    @Test
    @DisplayName("Phase 4 gate 3: simultaneous sales on two channels never drive stock negative — the excess is recorded as an oversell")
    void test3_ConcurrentSalesRecordOversellInsteadOfGoingNegative() {
        UUID variantId = insertVariant("SKU-LASTONE", 1);
        mapToChannel(connectionA, variantId, "SKU-LASTONE");
        mapToChannel(connectionB, variantId, "SKU-LASTONE");

        placeOrder(connectionA, "ORDER-A", "SKU-LASTONE", 1);
        placeOrder(connectionB, "ORDER-B", "SKU-LASTONE", 1);

        Map<String, Object> stock = jdbcTemplate.queryForMap(
                "SELECT on_hand, reserved FROM hub.stock WHERE organization_id = ? AND variant_id = ?", orgId, variantId);

        assertThat((Integer) stock.get("reserved"))
                .withFailMessage("Reserved must never exceed on_hand — sellable would be negative, which is not a quantity")
                .isLessThanOrEqualTo((Integer) stock.get("on_hand"));

        Integer oversells = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.oversell_event WHERE organization_id = ? AND variant_id = ?",
                Integer.class, orgId, variantId);
        assertThat(oversells)
                .withFailMessage("The sale we could not honour must be recorded, not silently absorbed")
                .isEqualTo(1);

        Map<String, Object> event = jdbcTemplate.queryForMap(
                "SELECT requested, available FROM hub.oversell_event WHERE organization_id = ?", orgId);
        assertThat(event.get("requested")).isEqualTo(1);
        assertThat(event.get("available")).isEqualTo(0);
    }

    // =========================================================================
    // Gate 4 — last-unit allocation
    // =========================================================================

    @Test
    @DisplayName("Phase 4 gate 4: with one unit left, exactly one channel is shown it and every other channel is pushed 0")
    void test4_LastUnitGoesToASingleChannel() {
        UUID variantId = insertVariant("SKU-ALLOC", 1);
        mapToChannel(connectionA, variantId, "SKU-ALLOC");
        mapToChannel(connectionB, variantId, "SKU-ALLOC");

        enqueueCurrentAvailability(variantId);

        int quantityA = pushedQuantity(connectionA, variantId);
        int quantityB = pushedQuantity(connectionB, variantId);

        assertThat(quantityA + quantityB)
                .withFailMessage("The last unit must be offered once in total (%d + %d), not once per channel", quantityA, quantityB)
                .isEqualTo(1);
        assertThat(quantityB)
                .withFailMessage("Allocation must follow allocation_priority, so the higher-priority connection B wins")
                .isEqualTo(1);
        assertThat(quantityA).isZero();
    }

    @Test
    @DisplayName("Phase 4 gate 4b: above the low-stock threshold every channel sees the pool, minus its own buffer")
    void test4b_BufferAppliesAboveTheThreshold() {
        UUID variantId = insertVariant("SKU-BUFFER", 20);
        mapToChannel(connectionA, variantId, "SKU-BUFFER");
        mapToChannel(connectionB, variantId, "SKU-BUFFER");
        setBuffer(connectionA, variantId, 5);

        enqueueCurrentAvailability(variantId);

        assertThat(pushedQuantity(connectionA, variantId))
                .withFailMessage("Channel A's buffer of 5 must be withheld from its advertised quantity")
                .isEqualTo(15);
        assertThat(pushedQuantity(connectionB, variantId))
                .withFailMessage("A buffer is per channel — it must not reduce what another channel sees")
                .isEqualTo(20);
    }

    // =========================================================================
    // Gate 5 — nightly channel drift is reported, never corrected
    // =========================================================================

    @Test
    @DisplayName("Phase 4 gate 5: deliberate channel drift is reported by the nightly reconcile and our own stock is left untouched")
    void test5_NightlyReconcileReportsDriftWithoutCorrectingIt() {
        // SKU-0 exists in mock-pazaryeri's seeded catalog, so the channel really returns it.
        UUID variantId = insertVariant("SKU-0", 42);
        mapToChannel(connectionA, variantId, "product-0");

        // The channel thinks it has 7; we think 42. Neither side is asked to yield.
        adminPost("/_admin/stock", "{\"SKU-0\": 7}");

        int reported = reconcileService.reconcileChannelStock(orgId, connectionA);
        assertThat(reported).isEqualTo(1);

        Map<String, Object> discrepancy = jdbcTemplate.queryForMap("""
                SELECT type, expected, actual, resolved FROM hub.stock_discrepancy
                WHERE organization_id = ? AND variant_id = ?
                """, orgId, variantId);
        assertThat(discrepancy.get("type")).isEqualTo("CHANNEL_DRIFT");
        assertThat(discrepancy.get("expected")).isEqualTo(42);
        assertThat(discrepancy.get("actual")).isEqualTo(7);
        assertThat(discrepancy.get("resolved")).isEqualTo(false);

        Integer onHand = jdbcTemplate.queryForObject(
                "SELECT on_hand FROM hub.stock WHERE organization_id = ? AND variant_id = ?",
                Integer.class, orgId, variantId);
        assertThat(onHand)
                .withFailMessage("Plan §0: drift is reported, never auto-corrected — our stock must be exactly as it was")
                .isEqualTo(42);
    }

    // =========================================================================
    // Gate 6 — the stock row must agree with its own ledger
    // =========================================================================

    @Test
    @DisplayName("Phase 4 gate 6: a stock row edited behind the ledger's back is caught by the internal consistency check")
    void test6_LedgerInconsistencyIsDetected() {
        UUID variantId = insertVariant("SKU-LEDGER", 30);

        // Corrupt the counter without a matching movement — exactly what a stray UPDATE,
        // or a write path that bypassed StockLedgerService, would leave behind.
        jdbcTemplate.update("UPDATE hub.stock SET on_hand = on_hand + 99 WHERE organization_id = ? AND variant_id = ?",
                orgId, variantId);

        int inconsistent = inTenantReturning(() -> stockConsistencyService.checkOrganization(orgId));
        assertThat(inconsistent).isEqualTo(1);

        Map<String, Object> discrepancy = jdbcTemplate.queryForMap("""
                SELECT type, expected, actual FROM hub.stock_discrepancy
                WHERE organization_id = ? AND variant_id = ? AND type = 'INTERNAL_INCONSISTENCY'
                """, orgId, variantId);
        assertThat(discrepancy.get("expected"))
                .withFailMessage("Expected must come from replaying the movements, which still add up to 30")
                .isEqualTo(30);
        assertThat(discrepancy.get("actual")).isEqualTo(129);
    }

    @Test
    @DisplayName("Phase 4 gate 6b: a consistent organization produces no discrepancies at all")
    void test6b_ConsistentLedgerReportsNothing() {
        UUID variantId = insertVariant("SKU-CLEAN", 12);
        inTenant(() -> stockLedgerService.recordReservedIncrease(orgId, variantId, 4, null, connectionA));

        assertThat(inTenantReturning(() -> stockConsistencyService.checkOrganization(orgId)))
                .withFailMessage("Normal ledger activity must not be reported as inconsistency, or the report becomes noise")
                .isZero();
    }

    // =========================================================================
    // Gate 7 — credentials invalid takes the channel out of service, loudly
    // =========================================================================

    @Test
    @DisplayName("Phase 4 gate 7: a channel that rejects our credentials is taken out of service and escalated to the operator queue")
    void test7_InvalidCredentialsBreakTheCircuitAndEscalate() {
        UUID variantId = insertVariant("SKU-AUTH", 5);
        mapToChannel(connectionA, variantId, "SKU-AUTH");
        enqueueCurrentAvailability(variantId);

        adminPost("/_admin/scenario", "{\"stockUpdateFails\": true, \"credentialsInvalid\": true}");

        int confirmed = pushSender.sendWindow(orgId, connectionA);
        assertThat(confirmed).isZero();

        assertThat(statusOfConnection(connectionA))
                .withFailMessage("A rejected credential must take the connection out of service, not just count as a failure")
                .isEqualTo("CREDENTIALS_INVALID");

        Integer queued = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM hub.operator_queue
                WHERE organization_id = ? AND type = 'CHANNEL_CREDENTIALS_INVALID' AND reference_id = ?
                """, Integer.class, orgId, connectionA);
        assertThat(queued)
                .withFailMessage("Nothing but a human can fix revoked credentials — it must reach the operator queue")
                .isEqualTo(1);

        assertThat(statusOf(connectionA, variantId))
                .withFailMessage("The undelivered push must be back at PENDING, not lost with the failed call")
                .isEqualTo("PENDING");

        assertThat(pushSender.sendWindow(orgId, connectionA))
                .withFailMessage("A connection marked CREDENTIALS_INVALID must not be called again")
                .isZero();
    }

    @Test
    @DisplayName("Phase 4 gate 7b: repeated transient failures open the circuit, and a later success closes it")
    void test7b_TransientFailuresOpenAndThenCloseTheCircuit() {
        UUID variantId = insertVariant("SKU-CIRCUIT", 5);
        mapToChannel(connectionA, variantId, "SKU-CIRCUIT");
        enqueueCurrentAvailability(variantId);

        // Fails the call but keeps credentials valid — the transient path, not the fatal one.
        adminPost("/_admin/scenario", "{\"stockUpdateFails\": true}");

        // circuit-failure-threshold is 2 for this test class.
        pushSender.sendWindow(orgId, connectionA);
        pushSender.sendWindow(orgId, connectionA);

        assertThat(statusOfConnection(connectionA)).isEqualTo("CIRCUIT_OPEN");

        adminPost("/_admin/scenario", "{\"stockUpdateFails\": false}");
        // Still open: the breaker holds the channel out for its backoff regardless of
        // the channel having quietly recovered.
        assertThat(pushSender.sendWindow(orgId, connectionA)).isZero();

        jdbcTemplate.update("UPDATE hub.channel_connection SET circuit_open_until = now() - interval '1 second' WHERE id = ?",
                connectionA);

        assertThat(pushSender.sendWindow(orgId, connectionA))
                .withFailMessage("Once the backoff elapses the channel must be tried again, not stay open forever")
                .isEqualTo(1);
        assertThat(statusOfConnection(connectionA))
                .withFailMessage("A successful call must close the circuit and return the connection to ACTIVE")
                .isEqualTo("ACTIVE");
    }

    // =========================================================================
    // Gate 8 — queue depth stays bounded under sustained load
    // =========================================================================

    @Test
    @DisplayName("Phase 4 gate 8: under sustained change load the pending push depth stays bounded by variant count, not change count")
    void test8_QueueDepthStaysBoundedUnderLoad() {
        int variantCount = 40;
        int changesPerVariant = 25;

        List<UUID> variantIds = new java.util.ArrayList<>();
        for (int i = 0; i < variantCount; i++) {
            UUID variantId = insertVariant("SKU-LOAD-" + i, 100);
            mapToChannel(connectionA, variantId, "SKU-LOAD-" + i);
            variantIds.add(variantId);
        }

        inTenant(() -> {
            for (int round = 0; round < changesPerVariant; round++) {
                for (UUID variantId : variantIds) {
                    stockLedgerService.recordSupply(orgId, variantId, 1, null);
                }
            }
        });

        int depth = pushStore.countPending(connectionA);
        assertThat(depth)
                .withFailMessage("%d changes must not queue %d rows — coalescing bounds the depth at the number of variants",
                        variantCount * changesPerVariant, depth)
                .isEqualTo(variantCount);

        int confirmed = pushSender.sendWindow(orgId, connectionA);

        assertThat(confirmed).isEqualTo(variantCount);
        assertThat(pushStore.countPending(connectionA))
                .withFailMessage("One window must drain the whole backlog — a depth that survives it grows without bound")
                .isZero();
        assertThat(stockUpdateCallCount())
                .withFailMessage("The entire backlog goes out in one batch call (Plan §8), not one call per variant")
                .isEqualTo(1);
    }

    // =========================================================================
    // Window scheduling — the task key must carry the window, or the task runs once ever
    // =========================================================================

    @Test
    @DisplayName("Phase 4: each send window becomes one work_batch row whose task key carries the window start")
    void windowSchedulerKeysTasksByWindowNotJustByConnection() {
        UUID variantId = insertVariant("SKU-WINDOW", 7);
        mapToChannel(connectionA, variantId, "SKU-WINDOW");
        enqueueCurrentAvailability(variantId);

        Instant firstWindow = Instant.ofEpochMilli(1_700_000_000_000L);
        Instant secondWindow = firstWindow.plusMillis(5000);

        // openWindows is cross-org by design, so its return count covers rows other tests
        // in this class left pending too — the assertions below scope to this org instead.
        windowScheduler.openWindows(firstWindow);
        assertThat(pushWindowTaskKeys()).containsExactly(connectionA + ":" + firstWindow);

        windowScheduler.openWindows(firstWindow);
        assertThat(pushWindowTaskKeys())
                .withFailMessage("Re-running the same window must not queue a second row for it")
                .containsExactly(connectionA + ":" + firstWindow);

        windowScheduler.openWindows(secondWindow);
        assertThat(pushWindowTaskKeys())
                .withFailMessage("A later window must get its own task — the engine's idempotency key never expires, "
                        + "so a key without a window component would let this connection push exactly once, ever")
                .containsExactlyInAnyOrder(connectionA + ":" + firstWindow, connectionA + ":" + secondWindow);
    }

    // =========================================================================
    // Fixtures and helpers
    // =========================================================================

    private UUID insertConnection(int allocationPriority) {
        EncryptedCredential encrypted = credentialEncryptionService.encrypt(baseUrl);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection
                    (id, organization_id, channel_type, encrypted_credentials, key_version, allocation_priority)
                VALUES (?, ?, 'MOCK', ?, ?, ?)
                """, id, orgId, encrypted.ciphertextBase64(), encrypted.keyVersion(), allocationPriority);
        return id;
    }

    /** Creates a variant with {@code onHand} units supplied through the ledger, so its movements add up. */
    private UUID insertVariant(String sku, int onHand) {
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.product (id, organization_id, title) VALUES (?, ?, ?)",
                productId, orgId, "Product " + sku);

        UUID variantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.variant (id, organization_id, product_id, sku) VALUES (?, ?, ?, ?)",
                variantId, orgId, productId, sku);

        if (onHand > 0) {
            inTenant(() -> stockLedgerService.recordSupply(orgId, variantId, onHand, null));
        }
        return variantId;
    }

    private void mapToChannel(UUID connectionId, UUID variantId, String channelVariantId) {
        jdbcTemplate.update("""
                INSERT INTO hub.channel_product_mapping
                    (id, organization_id, variant_id, channel_connection_id, channel_product_id, channel_variant_id, mapping_source)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, 'MANUAL')
                """, orgId, variantId, connectionId, channelVariantId, channelVariantId);
    }

    private void setBuffer(UUID connectionId, UUID variantId, int buffer) {
        jdbcTemplate.update("""
                INSERT INTO hub.stock_buffer (id, organization_id, channel_connection_id, variant_id, buffer)
                VALUES (gen_random_uuid(), ?, ?, ?, ?)
                """, orgId, connectionId, variantId, buffer);
    }

    /**
     * Forces an enqueue for a variant whose mappings were created after its stock was —
     * the ledger only enqueues to channels that were already mapped when it ran.
     */
    private void enqueueCurrentAvailability(UUID variantId) {
        inTenant(() -> stockLedgerService.recordSupply(orgId, variantId, 0, null));
    }

    private void placeOrder(UUID connectionId, String orderNumber, String sku, int quantity) {
        OrderEventPayload.OrderEventItem item = new OrderEventPayload.OrderEventItem(
                sku, sku, sku, null, quantity, new BigDecimal("19.99"), BigDecimal.ZERO, OrderItemStatus.CREATED);

        orderProcessingService.process(new OrderEventPayload(orgId, connectionId, "evt-" + orderNumber, orderNumber,
                Instant.now(), null, new BigDecimal("19.99"), "USD", List.of(item), null));
    }

    private List<String> pushWindowTaskKeys() {
        return jdbcTemplate.queryForList("""
                SELECT task_key FROM hub.work_batch
                WHERE organization_id = ? AND task_type = 'push-send' ORDER BY task_key
                """, String.class, orgId);
    }

    private int pushRowCount(UUID connectionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.channel_push WHERE channel_connection_id = ?", Integer.class, connectionId);
        return count == null ? 0 : count;
    }

    private long generationOf(UUID connectionId, UUID variantId) {
        return jdbcTemplate.queryForObject("""
                SELECT generation FROM hub.channel_push
                WHERE channel_connection_id = ? AND variant_id = ? AND type = 'STOCK'
                """, Long.class, connectionId, variantId);
    }

    private String statusOf(UUID connectionId, UUID variantId) {
        return jdbcTemplate.queryForObject("""
                SELECT status FROM hub.channel_push
                WHERE channel_connection_id = ? AND variant_id = ? AND type = 'STOCK'
                """, String.class, connectionId, variantId);
    }

    private String statusOfConnection(UUID connectionId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM hub.channel_connection WHERE id = ?", String.class, connectionId);
    }

    private int pushedQuantity(UUID connectionId, UUID variantId) {
        String json = jdbcTemplate.queryForObject("""
                SELECT CAST(target_value AS text) FROM hub.channel_push
                WHERE channel_connection_id = ? AND variant_id = ? AND type = 'STOCK'
                """, String.class, connectionId, variantId);
        return readJson(json).get("quantity").asInt();
    }

    private void awaitStatus(UUID connectionId, UUID variantId, String expected) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (expected.equals(statusOf(connectionId, variantId))) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("channel_push row never reached status " + expected
                + " (still " + statusOf(connectionId, variantId) + ")");
    }

    private int channelStock(String sku) {
        JsonNode stock = readJson(adminGet("/stock")).get("stockBySku").get(sku);
        assertThat(stock).withFailMessage("The channel was never told anything about %s", sku).isNotNull();
        return stock.asInt();
    }

    private int stockUpdateCallCount() {
        JsonNode counts = readJson(adminGet("/_admin/stats")).get("callCountsByPath");
        JsonNode calls = counts.get("/stock/bulk-update");
        return calls == null ? 0 : calls.asInt();
    }

    private void inTenant(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> {
            tenantContextService.setTransactionTenantContext(orgId);
            action.run();
        });
    }

    private <T> T inTenantReturning(java.util.function.Supplier<T> action) {
        return transactionTemplate.execute(status -> {
            tenantContextService.setTransactionTenantContext(orgId);
            return action.get();
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
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("mock-pazaryeri " + request.uri() + " returned " + response.statusCode());
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
