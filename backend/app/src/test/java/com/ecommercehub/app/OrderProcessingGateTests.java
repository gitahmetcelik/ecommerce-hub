package com.ecommercehub.app;

import com.ecommercehub.domain.order.OrderEventPayload;
import com.ecommercehub.domain.order.OrderItemStatus;
import com.ecommercehub.domain.order.OrderProcessingService;
import com.ecommercehub.domain.stock.StockReservationExpiryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * plan Faz 2 gate — the sipariş kalemi state machine (§6), reservation lifecycle
 * (§3), and derived-status recomputation, exercised directly against
 * OrderProcessingService (no HTTP/engine hop — see IngestGateTests for the outbox
 * plumbing that feeds this in production).
 *
 * <p>Since Faz 3, order processing routes SKUs through CatalogMatchingService
 * (plan §3) instead of auto-creating a variant — every SKU this suite exercises is
 * pre-seeded as an existing variant in setUp() so the SKU-match path resolves it,
 * matching what "an already-catalogued item" looks like in production. Faz 3's own
 * gate tests cover the unmatched path (mapping_candidate, operator queue).
 */
@SpringBootTest
public class OrderProcessingGateTests extends AbstractTestcontainersTest {

    private static final List<String> SEEDED_SKUS = List.of("SKU-A", "SKU-B", "SKU-X", "SKU-Y", "SKU-Z");

    @Autowired
    private OrderProcessingService orderProcessingService;

    @Autowired
    private StockReservationExpiryService stockReservationExpiryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orgId;
    private UUID channelConnectionId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");
        channelConnectionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection (id, organization_id, channel_type, encrypted_credentials)
                VALUES (?, ?, 'MOCK', 'n/a')
                """, channelConnectionId, orgId);

        for (String sku : SEEDED_SKUS) {
            seedVariant(sku);
        }
    }

    /**
     * Seeded with stock on hand, not just as a catalogue entry. Since Faz 4 the ledger
     * refuses to reserve or ship units that do not exist — it records an oversell_event
     * and holds the counters at zero instead of letting them go negative. A variant with
     * no stock therefore makes every order here an oversell, which is a different
     * scenario from the reservation and state-machine behaviour these Faz 2 gates are
     * about. Supplying stock first is what "an already-catalogued item" actually looks
     * like in production.
     */
    private void seedVariant(String sku) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.product (id, organization_id, title) VALUES (?, ?, ?)", productId, orgId, sku);
        jdbcTemplate.update("INSERT INTO hub.variant (id, organization_id, product_id, sku) VALUES (?, ?, ?, ?)",
                variantId, orgId, productId, sku);

        jdbcTemplate.update("""
                INSERT INTO hub.stock (id, organization_id, variant_id, on_hand) VALUES (gen_random_uuid(), ?, ?, 100)
                """, orgId, variantId);
        jdbcTemplate.update("""
                INSERT INTO hub.stock_movement (id, organization_id, variant_id, quantity, reason)
                VALUES (gen_random_uuid(), ?, ?, 100, 'ON_HAND_INCREASE')
                """, orgId, variantId);
    }

    private OrderEventPayload.OrderEventItem item(String sku, OrderItemStatus target) {
        return new OrderEventPayload.OrderEventItem(sku, sku, sku, null, 1, new BigDecimal("19.99"), BigDecimal.ZERO, target);
    }

    private OrderEventPayload singleItemEvent(String orderNumber, String sku, OrderItemStatus target,
                                               Long sequence, Instant eventAt) {
        return new OrderEventPayload(orgId, channelConnectionId, UUID.randomUUID().toString(), orderNumber,
                eventAt, sequence, new BigDecimal("19.99"), "USD", List.of(item(sku, target)),
                UUID.randomUUID().toString());
    }

    @Test
    @DisplayName("Faz 2 gate: payment.succeeded arriving before order.created still ends with the item PAID")
    void paymentBeforeOrderCreationStillEndsPaid() {
        String orderNumber = "CO-" + UUID.randomUUID();
        Instant t0 = Instant.now();

        // payment.succeeded arrives FIRST — no order exists yet.
        orderProcessingService.process(singleItemEvent(orderNumber, "SKU-A", OrderItemStatus.PAID, 2L, t0));
        // order.created arrives SECOND, describing an earlier moment in the order's life.
        orderProcessingService.process(singleItemEvent(orderNumber, "SKU-A", OrderItemStatus.CREATED, 1L, t0.minusSeconds(5)));

        String status = itemStatus(orderNumber);
        assertThat(status)
                .withFailMessage("Rank comparison must keep the item at PAID — CREATED(10) is not newer than PAID(30)")
                .isEqualTo("PAID");
    }

    @Test
    @DisplayName("Faz 2 gate: two same-second events with different sequences are both processed, not silently dropped")
    void sameSecondEventsBothProcessed() {
        String orderNumber = "CO-" + UUID.randomUUID();
        Instant sameSecond = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        orderProcessingService.process(singleItemEvent(orderNumber, "SKU-A", OrderItemStatus.CREATED, 1L, sameSecond));
        orderProcessingService.process(singleItemEvent(orderNumber, "SKU-A", OrderItemStatus.PAID, 2L, sameSecond));

        assertThat(itemStatus(orderNumber))
                .withFailMessage("The v2 same-timestamp bug would have silently dropped the second event; sequence must resolve it instead")
                .isEqualTo("PAID");
    }

    @Test
    @DisplayName("Faz 2 gate: the same transition applied 3 times has one effect, not three (target-status idempotency)")
    void sameTransitionAppliedThreeTimesHasOneEffect() {
        String orderNumber = "CO-" + UUID.randomUUID();
        Instant t0 = Instant.now();

        orderProcessingService.process(singleItemEvent(orderNumber, "SKU-A", OrderItemStatus.CREATED, 1L, t0));
        for (int i = 0; i < 3; i++) {
            orderProcessingService.process(singleItemEvent(orderNumber, "SKU-A", OrderItemStatus.SHIPPED, 2L, t0.plusSeconds(1)));
        }

        assertThat(itemStatus(orderNumber)).isEqualTo("SHIPPED");

        UUID variantId = variantIdForSku("SKU-A");
        Integer onHandDecreases = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.stock_movement WHERE variant_id = ? AND reason = 'ON_HAND_DECREASE'",
                Integer.class, variantId);
        assertThat(onHandDecreases)
                .withFailMessage("Re-applying an already-reached target status must not decrement stock a second or third time")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("Faz 2 gate: creating an item reserves stock; an unpaid reservation past 24h times the item out and releases the hold")
    void unpaidReservationExpiresAndReleasesStock() {
        String orderNumber = "CO-" + UUID.randomUUID();
        orderProcessingService.process(singleItemEvent(orderNumber, "SKU-B", OrderItemStatus.CREATED, 1L, Instant.now()));

        UUID variantId = variantIdForSku("SKU-B");
        Map<String, Object> stockAfterCreate = jdbcTemplate.queryForMap(
                "SELECT reserved FROM hub.stock WHERE variant_id = ?", variantId);
        assertThat(((Number) stockAfterCreate.get("reserved")).intValue()).isEqualTo(1);

        // Simulate the 24h window having already elapsed.
        jdbcTemplate.update("UPDATE hub.stock_reservation SET expires_at = ? WHERE variant_id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), variantId);

        int released = stockReservationExpiryService.releaseExpiredReservations(orgId);
        assertThat(released).isEqualTo(1);

        assertThat(itemStatus(orderNumber)).isEqualTo("PAYMENT_TIMEOUT");
        Map<String, Object> stockAfterExpiry = jdbcTemplate.queryForMap(
                "SELECT reserved FROM hub.stock WHERE variant_id = ?", variantId);
        assertThat(((Number) stockAfterExpiry.get("reserved")).intValue()).isZero();
    }

    @Test
    @DisplayName("Faz 2 gate: a 3-item order with one item cancelled derives PARTIALLY_CANCELLED")
    void threeItemOrderWithOneCancelledIsPartiallyCancelled() {
        String orderNumber = "CO-" + UUID.randomUUID();
        Instant t0 = Instant.now();

        OrderEventPayload createAll = new OrderEventPayload(orgId, channelConnectionId, UUID.randomUUID().toString(),
                orderNumber, t0, 1L, new BigDecimal("59.97"), "USD",
                List.of(item("SKU-X", OrderItemStatus.CREATED), item("SKU-Y", OrderItemStatus.CREATED), item("SKU-Z", OrderItemStatus.CREATED)),
                UUID.randomUUID().toString());
        orderProcessingService.process(createAll);

        OrderEventPayload cancelOne = new OrderEventPayload(orgId, channelConnectionId, UUID.randomUUID().toString(),
                orderNumber, t0.plusSeconds(10), 2L, new BigDecimal("59.97"), "USD",
                List.of(item("SKU-X", OrderItemStatus.CANCELLED)),
                UUID.randomUUID().toString());
        orderProcessingService.process(cancelOne);

        String derivedStatus = jdbcTemplate.queryForObject(
                "SELECT derived_status FROM hub.sales_order WHERE channel_order_number = ?", String.class, orderNumber);
        assertThat(derivedStatus).isEqualTo("PARTIALLY_CANCELLED");
    }

    private String itemStatus(String orderNumber) {
        return jdbcTemplate.queryForObject("""
                SELECT oi.status FROM hub.order_item oi
                JOIN hub.sales_order so ON so.id = oi.sales_order_id
                WHERE so.channel_order_number = ?
                """, String.class, orderNumber);
    }

    private UUID variantIdForSku(String sku) {
        // organization_id filter is required even though jdbcTemplate is the
        // superuser connection (bypasses RLS) — other test methods in this class
        // reuse "SKU-A" under their own orgId, and without this filter the query
        // sees every org's matching variant, not just this test's.
        return jdbcTemplate.queryForObject(
                "SELECT id FROM hub.variant WHERE sku = ? AND organization_id = ?", UUID.class, sku, orgId);
    }
}
