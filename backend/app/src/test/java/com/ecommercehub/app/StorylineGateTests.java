package com.ecommercehub.app;

import com.ecommercehub.app.push.ChannelPushSender;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.security.EncryptedCredential;
import com.ecommercehub.domain.stock.StockLedgerService;
import com.ecommercehub.domain.tenant.TenantContextService;
import com.ecommercehub.dispatcher.WorkBatchDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The channel drives, the hub reacts.
 *
 * <p>Every earlier ingest test built its own webhook and handed it to the controller —
 * which checks the hub against the hub's own idea of what a channel sends. Here
 * mock-pazaryeri decides the shape, the ordering, the timing and the duplicates, signs
 * each body itself, and POSTs over real HTTP into the running application. The HMAC is
 * therefore computed over the bytes that actually travelled, not over bytes a test
 * intended to send.
 *
 * <p>This is what replaces a real marketplace: the hard cases a live API would produce
 * occasionally and unrepeatably are produced here deliberately and on demand.
 */
// One work_batch row per dispatch cycle. Without this the test hands the engine four
// events at once and they are processed concurrently, which makes half of them arrive at
// the state machine out of order, defer (plan §6 ERTELENDI), and then wait out the
// engine's exponential backoff. That concurrency is the test's own invention — a real
// channel spaces its events out, and the storylines already model the ordering hazards
// that matter deliberately rather than by accident.
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"hub.dispatcher.per-org-per-cycle=1", "hub.dispatcher.total-per-cycle=1"})
public class StorylineGateTests extends AbstractTestcontainersTest {

    private static final int PORT = 4100;
    private static final Path MOCK_PAZARYERI_DIR = Paths.get("../../mock-pazaryeri").toAbsolutePath().normalize();

    private static final GenericContainer<?> mockPazaryeri =
            new GenericContainer<>(new ImageFromDockerfile().withFileFromPath(".", MOCK_PAZARYERI_DIR))
                    .withExposedPorts(PORT);

    /**
     * Started lazily rather than in a static block, because the host port forwarding has
     * to exist BEFORE the container does. Testcontainers implements
     * host.testcontainers.internal with a side-car it wires into containers as they
     * start; a container started earlier simply has no route back to the host, and every
     * webhook the marketplace pushes disappears into a connection error.
     *
     * <p>The application's port is only known once Spring has started, which is why this
     * cannot happen in a static initialiser at all.
     */
    private static boolean started;

    private static synchronized void startMockOnce(int hubPort) {
        if (started) {
            return;
        }
        Testcontainers.exposeHostPorts(hubPort);
        mockPazaryeri.start();
        started = true;
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort private int hubPort;

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TenantContextService tenantContextService;
    @Autowired private StockLedgerService stockLedgerService;
    @Autowired private WorkBatchDispatcher dispatcher;
    @Autowired private ChannelPushSender pushSender;
    @Autowired private CredentialEncryptionService credentialEncryptionService;
    @Autowired private ObjectMapper objectMapper;

    private UUID orgId;
    private UUID channelConnectionId;
    private String mockUrl;

    @BeforeEach
    void setUp() {
        startMockOnce(hubPort);
        mockUrl = "http://" + mockPazaryeri.getHost() + ":" + mockPazaryeri.getMappedPort(PORT);
        post(mockUrl + "/_admin/reset", "{}");

        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");

        EncryptedCredential encrypted = credentialEncryptionService.encrypt(mockUrl);
        channelConnectionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection (id, organization_id, channel_type, encrypted_credentials, key_version)
                VALUES (?, ?, 'MOCK', ?, ?)
                """, channelConnectionId, orgId, encrypted.ciphertextBase64(), encrypted.keyVersion());
    }

    @Test
    @DisplayName("Storyline: a channel-pushed happy path arrives, verifies its own signature and ends DELIVERED")
    void happyPathArrivesOverTheWire() {
        seedVariant("SKU-0", 50);

        JsonNode result = runStoryline("happy-path", "{\"orderNumber\":\"SC-HAPPY\",\"sku\":\"SKU-0\"}");

        assertThat(deliveredStatuses(result))
                .withFailMessage("Every push must be accepted. The marketplace reported: %s", result.get("delivered"))
                .allMatch(status -> status == 200);

        dispatchAll();

        // "Four events pushed in order end at the last of them" is the claim; how long
        // the engine takes to get there is not.
        awaitItemStatus("SC-HAPPY", "DELIVERED");
    }

    @Test
    @DisplayName("Storyline: payment arriving before the order it pays for still lands correctly")
    void outOfOrderDeliveryIsAbsorbed() {
        seedVariant("SKU-1", 50);

        runStoryline("out-of-order", "{\"orderNumber\":\"SC-OOO\",\"sku\":\"SKU-1\"}");
        dispatchAll();

        // Ordering the source never had cannot be restored downstream — the state machine
        // absorbs it (plan §0), and PAID is not undone by the order.created that follows.
        awaitItemStatus("SC-OOO", "PAID");
    }

    @Test
    @DisplayName("Storyline: the same event delivered three times is recorded once")
    void duplicateDeliveryIsDeduplicated() {
        seedVariant("SKU-2", 50);

        runStoryline("duplicate-delivery", "{\"orderNumber\":\"SC-DUP\",\"sku\":\"SKU-2\"}");

        // Deduplication happens synchronously inside ingest, so this needs no waiting —
        // and asserting it immediately is what proves it was not merely eventual.
        Integer rawEvents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.raw_event WHERE organization_id = ?", Integer.class, orgId);
        assertThat(rawEvents)
                .withFailMessage("At-least-once delivery is the channel's guarantee; exactly-once effect is ours")
                .isEqualTo(1);

        Integer workBatches = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.work_batch WHERE organization_id = ?", Integer.class, orgId);
        assertThat(workBatches)
                .withFailMessage("A duplicate that got past ingest would still queue a second unit of work")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Storyline: two events sharing one timestamp are both applied — the sequence resolves them")
    void sameSecondEventsAreBothApplied() {
        seedVariant("SKU-3", 50);

        runStoryline("same-second", "{\"orderNumber\":\"SC-SEC\",\"sku\":\"SKU-3\"}");
        dispatchAll();

        // plan §6 (v3): the timestamp rule alone dropped the second same-second event
        // silently; the sequence number is what resolves the pair.
        awaitItemStatus("SC-SEC", "PAID");
    }

    @Test
    @DisplayName("Storyline: one cancelled line out of three derives PARTIALLY_CANCELLED")
    void partialCancellationDerivesMixedStatus() {
        seedVariant("SKU-4", 50);
        seedVariant("SKU-5", 50);
        seedVariant("SKU-6", 50);

        runStoryline("partial-cancel", "{\"orderNumber\":\"SC-PARTIAL\"}");
        dispatchAll();

        // Order status is derived from its lines (plan §0) — a single column set by
        // whoever wrote last could not express this at all.
        awaitDerivedStatus("SC-PARTIAL", "PARTIALLY_CANCELLED");
    }

    @Test
    @DisplayName("Storyline: an item the hub has never seen is queued for a human, not invented and not dropped")
    void unknownItemIsQueuedForReview() {
        runStoryline("unknown-item", "{\"orderNumber\":\"SC-UNKNOWN\"}");
        dispatchAll();

        // plan §3: an unmatched line is never silently dropped — its stock cannot be
        // deducted, so dropping it is an order that quietly never touched inventory.
        awaitCount("the unmatched line to reach the review queue",
                "SELECT count(*) FROM hub.mapping_candidate WHERE organization_id = ? AND status = 'PENDING'", 1);

        Integer variants = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.variant WHERE organization_id = ? AND sku = 'SKU-NEVER-SEEN'",
                Integer.class, orgId);
        assertThat(variants)
                .withFailMessage("Nor is it invented — auto-creating a variant on the order path is how a typo "
                        + "becomes a product")
                .isZero();
    }

    @Test
    @DisplayName("Storyline: two channel-pushed orders racing for the last unit record an oversell, stock stays sane")
    void oversellRaceIsRecorded() {
        UUID variantId = seedVariant("SKU-7", 1);

        runStoryline("oversell-race", "{\"sku\":\"SKU-7\"}");
        dispatchAll();

        awaitCount("the unfulfillable sale to be recorded",
                "SELECT count(*) FROM hub.oversell_event WHERE organization_id = ?", 1);

        var stock = jdbcTemplate.queryForMap(
                "SELECT on_hand, reserved FROM hub.stock WHERE organization_id = ? AND variant_id = ?",
                orgId, variantId);
        assertThat((Integer) stock.get("reserved"))
                .withFailMessage("Reserved must never exceed on_hand — sellable would be negative, which is not a quantity")
                .isLessThanOrEqualTo((Integer) stock.get("on_hand"));

    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Asks the marketplace to play a storyline at this application instance. */
    private JsonNode runStoryline(String name, String paramsJson) {
        String hubUrl = "http://host.testcontainers.internal:" + hubPort;
        String body = """
                {"hubUrl":"%s","organizationId":"%s","channelConnectionId":"%s","storyline":"%s","params":%s}
                """.formatted(hubUrl, orgId, channelConnectionId, name, paramsJson);

        return readJson(post(mockUrl + "/_admin/storylines/run", body));
    }

    private List<Integer> deliveredStatuses(JsonNode result) {
        List<Integer> statuses = new java.util.ArrayList<>();
        result.get("delivered").forEach(entry -> statuses.add(entry.path("status").asInt()));
        return statuses;
    }

    /**
     * Starts the outbox moving. The scheduled dispatcher is off in tests (see
     * application.properties), so rows have to be pulled through by hand; {@link #await}
     * keeps pulling as it polls, one row per cycle.
     *
     * <p>This does not wait for anything. How long the engine takes is not a property
     * worth asserting on — the awaitX helpers wait for the outcome the test is about.
     */
    private void dispatchAll() {
        dispatcher.dispatchCycle();
    }

    /**
     * Waits for a condition on the database rather than for the engine's own bookkeeping.
     *
     * <p>An earlier version waited until every task row reached a terminal state, which
     * looks equivalent and is not: the queues are shared with other cached Spring
     * contexts, so a task can sit queued well past any sane deadline while the work this
     * test cares about has already happened — or has not happened yet when the deadline
     * expires. Asserting on the outcome removes the guess entirely.
     */
    private void await(String description, java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            // Feeds the next event only once the previous one has been picked up, so the
            // outbox drains in the order the channel sent it.
            dispatcher.dispatchCycle();
            sleep(150);
        }
        throw new AssertionError("Timed out waiting for " + description + ". " + diagnostics());
    }

    private void awaitItemStatus(String channelOrderNumber, String expected) {
        await("order " + channelOrderNumber + " to reach " + expected,
                () -> expected.equals(itemStatusOrNull(channelOrderNumber)));
    }

    private void awaitDerivedStatus(String channelOrderNumber, String expected) {
        await("order " + channelOrderNumber + " to derive " + expected,
                () -> expected.equals(derivedStatusOrNull(channelOrderNumber)));
    }

    private void awaitCount(String description, String sql, int expected) {
        await(description, () -> {
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class, orgId);
            return count != null && count == expected;
        });
    }

    private String itemStatusOrNull(String channelOrderNumber) {
        List<String> statuses = jdbcTemplate.queryForList("""
                SELECT oi.status FROM hub.order_item oi
                JOIN hub.sales_order so ON so.id = oi.sales_order_id
                WHERE so.organization_id = ? AND so.channel_order_number = ?
                """, String.class, orgId, channelOrderNumber);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private String derivedStatusOrNull(String channelOrderNumber) {
        List<String> statuses = jdbcTemplate.queryForList("""
                SELECT derived_status FROM hub.sales_order
                WHERE organization_id = ? AND channel_order_number = ?
                """, String.class, orgId, channelOrderNumber);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    /** Everything the outbox and the engine know about this org — the first thing to look at when nothing landed. */
    private String diagnostics() {
        return "work_batch=" + jdbcTemplate.queryForList("""
                SELECT wb.task_type, wb.status, g.durum, g.hata
                FROM hub.work_batch wb LEFT JOIN motor.gorevler g ON g.id = wb.task_id
                WHERE wb.organization_id = ?
                """, orgId)
                + " raw_events=" + jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM hub.raw_event WHERE organization_id = ?", Integer.class, orgId)
                + " orders=" + jdbcTemplate.queryForList(
                        "SELECT channel_order_number, derived_status FROM hub.sales_order WHERE organization_id = ?", orgId);
    }

    private UUID seedVariant(String sku, int onHand) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.product (id, organization_id, title) VALUES (?, ?, ?)",
                productId, orgId, "Product " + sku);
        jdbcTemplate.update("INSERT INTO hub.variant (id, organization_id, product_id, sku) VALUES (?, ?, ?, ?)",
                variantId, orgId, productId, sku);

        transactionTemplate.executeWithoutResult(status -> {
            tenantContextService.setTransactionTenantContext(orgId);
            stockLedgerService.recordSupply(orgId, variantId, onHand, null);
        });
        return variantId;
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Unparseable mock-pazaryeri response: " + body, e);
        }
    }

    private String post(String url, String body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("mock-pazaryeri " + url + " returned "
                        + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("mock-pazaryeri call failed: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
