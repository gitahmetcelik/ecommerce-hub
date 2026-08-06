package com.ecommercehub.app;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommercehub.app.retention.RawEventPartitionMaintenanceService;
import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.customer.CustomerErasureService;
import com.ecommercehub.ingest.IngestService;
import com.ecommercehub.domain.order.OrderEventPayload;
import com.ecommercehub.domain.order.OrderItemStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plan §12 Phase 7: PII and retention hardening.
 *
 * <p>The Trendyol connector, the other half of Phase 7, is out of scope by the same
 * decision that dropped Shopify from Phase 6 ("Mock connector only" — Plan §14's real-API
 * checks were never run). Everything here is channel-independent and would be needed
 * whichever real channel arrives first.
 */
@SpringBootTest
public class Faz7PiiGateTests extends AbstractTestcontainersTest {

    private static final String CUSTOMER_EMAIL = "ayse.yilmaz@example.com";
    private static final String CUSTOMER_PHONE = "+905551234567";
    private static final String CUSTOMER_ADDRESS = "Bagdat Caddesi 42, Kadikoy, Istanbul";

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private CustomerErasureService erasureService;
    @Autowired private RawEventPartitionMaintenanceService partitionMaintenance;
    @Autowired private IngestService ingestService;

    private UUID orgId;
    private UUID channelConnectionId;
    private UUID customerId;
    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");

        channelConnectionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection (id, organization_id, channel_type, encrypted_credentials)
                VALUES (?, ?, 'MOCK', 'n/a')
                """, channelConnectionId, orgId);

        customerId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.customer (id, organization_id, first_name, last_name, email, phone, address)
                VALUES (?, ?, 'Ayse', 'Yilmaz', ?, ?, ?)
                """, customerId, orgId, CUSTOMER_EMAIL, CUSTOMER_PHONE, CUSTOMER_ADDRESS);

        logCapture = new ListAppender<>();
        logCapture.start();
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("com.ecommercehub").addAppender(logCapture);
    }

    @AfterEach
    void tearDown() {
        ((LoggerContext) LoggerFactory.getILoggerFactory()).getLogger("com.ecommercehub").detachAppender(logCapture);
    }

    // =========================================================================
    // Gate: an erasure request reaches the raw event bodies, not just the customer row
    // =========================================================================

    @Test
    @DisplayName("Phase 7 gate: erasing a customer anonymises the row AND redacts their details from stored event bodies")
    void erasureReachesRawEventBodies() {
        UUID rawEventId = insertRawEventContaining(CUSTOMER_EMAIL, CUSTOMER_ADDRESS);

        var result = erasureService.erase(actor(HubRole.ADMIN), customerId);

        assertThat(result.maskedRawEvents())
                .withFailMessage("Partitions expire data in bulk; they do nothing for one person asking today")
                .isEqualTo(1);

        var customer = jdbcTemplate.queryForMap(
                "SELECT first_name, last_name, email, phone, address, erased_at FROM hub.customer WHERE id = ?",
                customerId);
        assertThat(customer.get("first_name")).isEqualTo("ERASED");
        assertThat(customer.get("email")).isNull();
        assertThat(customer.get("phone")).isNull();
        assertThat(customer.get("address")).isNull();
        assertThat(customer.get("erased_at")).isNotNull();

        String body = rawBodyOf(rawEventId);
        assertThat(body)
                .withFailMessage("The address sat verbatim inside the event body — an erasure that leaves it there erases nothing")
                .doesNotContain(CUSTOMER_ADDRESS)
                .doesNotContain(CUSTOMER_EMAIL)
                .contains("[REDACTED]");
    }

    @Test
    @DisplayName("Phase 7 gate: another customer's events are untouched — redaction is targeted, not a wipe")
    void erasureDoesNotTouchOtherCustomersEvents() {
        UUID otherEventId = insertRawEventContaining("baska.musteri@example.com", "Some other street 7");
        insertRawEventContaining(CUSTOMER_EMAIL, CUSTOMER_ADDRESS);

        erasureService.erase(actor(HubRole.ADMIN), customerId);

        assertThat(rawBodyOf(otherEventId))
                .withFailMessage("Over-broad redaction destroys the operational record without helping anyone's privacy")
                .contains("baska.musteri@example.com")
                .doesNotContain("[REDACTED]");
    }

    @Test
    @DisplayName("Phase 7 gate: a repeated erasure request is a no-op, not a second pass")
    void erasureIsIdempotent() {
        insertRawEventContaining(CUSTOMER_EMAIL, CUSTOMER_ADDRESS);

        assertThat(erasureService.erase(actor(HubRole.ADMIN), customerId).maskedRawEvents()).isEqualTo(1);

        var second = erasureService.erase(actor(HubRole.ADMIN), customerId);
        assertThat(second.alreadyErased()).isTrue();
        assertThat(second.maskedRawEvents()).isZero();
    }

    @Test
    @DisplayName("Phase 7 gate: erasure is ADMIN-only — it cannot be undone, so it sits where moving money sits")
    void erasureRequiresAdmin() {
        assertThatThrownBy(() -> erasureService.erase(actor(HubRole.OPERATOR), customerId))
                .isInstanceOf(InsufficientRoleException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT first_name FROM hub.customer WHERE id = ?", String.class, customerId))
                .isEqualTo("Ayse");
    }

    // =========================================================================
    // Gate: no PII in the logs
    // =========================================================================

    @Test
    @DisplayName("Phase 7 gate: ingesting a body full of personal data logs none of it")
    void ingestNeverLogsPersonalData() {
        String body = """
                {"orderNumber":"CO-9001","customer":{"email":"%s","phone":"%s","address":"%s"}}
                """.formatted(CUSTOMER_EMAIL, CUSTOMER_PHONE, CUSTOMER_ADDRESS);

        ingestService.ingest(orgId, channelConnectionId, "evt-pii-1", body, "sig", "trace-1", orderPayload());
        // A duplicate delivery too: that path logs about the event, which is exactly the
        // kind of "helpful" log line that quietly carries a body into a log aggregator.
        ingestService.ingest(orgId, channelConnectionId, "evt-pii-1", body, "sig", "trace-1", orderPayload());

        assertNoPiiInLogs();
    }

    @Test
    @DisplayName("Phase 7 gate: the erasure path itself does not log what it is erasing")
    void erasureNeverLogsWhatItErased() {
        insertRawEventContaining(CUSTOMER_EMAIL, CUSTOMER_ADDRESS);
        erasureService.erase(actor(HubRole.ADMIN), customerId);

        assertNoPiiInLogs();
    }

    // =========================================================================
    // Gate: retention drops whole partitions
    // =========================================================================

    @Test
    @DisplayName("Phase 7 gate: a raw_event partition past the retention window is dropped, not scanned row by row")
    void oldRawEventPartitionsAreDropped() {
        // Deliberately years outside the window, not merely months: the previous
        // implementation only looked 36 months back and would have left this one holding
        // personal data forever.
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS hub.raw_event_y2020_m01
                PARTITION OF hub.raw_event FOR VALUES FROM ('2020-01-01') TO ('2020-02-01')
                """);
        assertThat(partitionExists("raw_event_y2020_m01")).isTrue();

        partitionMaintenance.dropExpiredPartitions();

        assertThat(partitionExists("raw_event_y2020_m01"))
                .withFailMessage("Expiry of an append-only audit trail is a DROP — that is the whole reason it is partitioned")
                .isFalse();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Scans everything the application logged for any of the seeded personal values.
     *
     * <p>Deliberately checks the rendered message rather than the format string: PII
     * almost never appears in a literal, it arrives as a parameter.
     */
    private void assertNoPiiInLogs() {
        List<String> messages = logCapture.list.stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.DEBUG))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        for (String pii : List.of(CUSTOMER_EMAIL, CUSTOMER_PHONE, CUSTOMER_ADDRESS)) {
            assertThat(messages)
                    .withFailMessage("A log line carried personal data (%s). Logs get shipped, indexed and kept far "
                            + "longer than the data they quote.", pii)
                    .noneMatch(message -> message.contains(pii));
        }
    }

    private UUID insertRawEventContaining(String email, String address) {
        UUID id = UUID.randomUUID();
        String body = """
                {"orderNumber":"CO-%s","customer":{"email":"%s","address":"%s"}}
                """.formatted(id.toString().substring(0, 8), email, address);

        jdbcTemplate.update("""
                INSERT INTO hub.raw_event (id, organization_id, channel_connection_id, channel_event_id, raw_body)
                VALUES (?, ?, ?, ?, ?)
                """, id, orgId, channelConnectionId, "evt-" + id, body);
        return id;
    }

    private String rawBodyOf(UUID rawEventId) {
        return jdbcTemplate.queryForObject("SELECT raw_body FROM hub.raw_event WHERE id = ?", String.class, rawEventId);
    }

    private boolean partitionExists(String name) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'hub' AND c.relname = ?
                """, Integer.class, name);
        return count != null && count > 0;
    }

    private AuthenticatedUser actor(HubRole role) {
        return new AuthenticatedUser(UUID.randomUUID(), orgId, "actor@example.com", List.of(role));
    }

    private OrderEventPayload orderPayload() {
        return new OrderEventPayload(orgId, channelConnectionId, "evt-pii-1", "CO-9001", Instant.now(), null,
                new BigDecimal("10.00"), "USD",
                List.of(new OrderEventPayload.OrderEventItem("SKU-PII", "SKU-PII", "SKU-PII", null, 1,
                        new BigDecimal("10.00"), BigDecimal.ZERO, OrderItemStatus.CREATED)),
                null);
    }
}
