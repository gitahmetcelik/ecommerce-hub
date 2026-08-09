package com.ecommercehub.app;

import com.ecommercehub.domain.order.OrderEventPayload;
import com.ecommercehub.domain.order.OrderItemStatus;
import com.ecommercehub.domain.order.OrderProcessingService;
import com.ecommercehub.domain.tenant.TenantContextService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bug found running Faz 8's real-Shopify verification: {@link OrderProcessingService#process}
 * is {@code @Transactional} with REQUIRED propagation, so calling it in a loop from an
 * already-transactional caller (backfill, reconcile) makes it <em>join</em> that
 * caller's transaction. When one order throws — a deferred transition is the
 * reachable-without-mocks case here, but any exception has the same effect — Spring
 * marks the whole shared transaction rollback-only the instant the exception crosses
 * {@code process}'s own proxy boundary. Catching it at the call site does not undo
 * that: the caller's transaction fails at commit with {@link UnexpectedRollbackException}
 * regardless, discarding every sibling order already processed in the same page.
 * {@link OrderProcessingService#processIsolated} fixes this with its own
 * {@code REQUIRES_NEW} transaction. This test reproduces the exact mechanism directly,
 * independent of BackfillService/ReconcileService's connector plumbing.
 */
@SpringBootTest
public class OrderBatchIsolationGateTests extends AbstractTestcontainersTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TenantContextService tenantContextService;
    @Autowired private OrderProcessingService orderProcessingService;

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
    }

    @Test
    @DisplayName("Bugfix gate: processIsolated moves an order's failure into its own transaction, so it can't roll back sibling orders in the caller's shared transaction")
    void oneOrderFailingDoesNotRollBackSiblingsInSharedTransaction() {
        UUID siblingSku = seedVariant("SKU-SIBLING");
        UUID poisonSku = seedVariant("SKU-POISON");

        // Drives the poison order's item to SHIPPED, so a later CANCELLED event for it
        // is a terminal target unreachable from SHIPPED (Plan §6's transition table) —
        // a deterministic DEFER, no mocking of internals required.
        orderProcessingService.process(orderEvent("CO-POISON", "SKU-POISON", 1L, OrderItemStatus.CREATED));
        orderProcessingService.process(orderEvent("CO-POISON", "SKU-POISON", 2L, OrderItemStatus.SHIPPED));

        UnexpectedRollbackException unexpectedRollback = null;
        try {
            transactionTemplate.executeWithoutResult(status -> {
                tenantContextService.setTransactionTenantContext(orgId);

                // Sibling work in the same shared transaction — stands in for "other
                // orders already processed earlier in the same backfill/reconcile page."
                orderProcessingService.processIsolated(orderEvent("CO-SIBLING", "SKU-SIBLING", 1L, OrderItemStatus.CREATED));

                // The poison order: DEFER, so process() throws. A batch caller (Backfill/
                // ReconcileService) catches this and moves on to the next order.
                try {
                    orderProcessingService.processIsolated(orderEvent("CO-POISON", "SKU-POISON", 3L, OrderItemStatus.CANCELLED));
                } catch (RuntimeException expected) {
                    // exactly what BackfillService.runOrderPage / ReconcileService.reconcileOpenOrders now do
                }
            });
        } catch (UnexpectedRollbackException e) {
            unexpectedRollback = e;
        }

        assertThat(unexpectedRollback)
                .withFailMessage("A caught per-order failure must not still fail the whole page's commit")
                .isNull();

        Integer siblingOrderCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.sales_order WHERE organization_id = ? AND channel_order_number = ?",
                Integer.class, orgId, "CO-SIBLING");
        assertThat(siblingOrderCount)
                .withFailMessage("The sibling order processed earlier in the same shared transaction must have committed")
                .isEqualTo(1);

        String poisonItemStatus = jdbcTemplate.queryForObject("""
                SELECT oi.status FROM hub.order_item oi
                JOIN hub.sales_order so ON so.id = oi.sales_order_id
                WHERE so.organization_id = ? AND so.channel_order_number = ?
                """, String.class, orgId, "CO-POISON");
        assertThat(poisonItemStatus)
                .withFailMessage("The poison order's own failed transition must not have applied")
                .isEqualTo("SHIPPED");
    }

    private UUID seedVariant(String sku) {
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.product (id, organization_id, title) VALUES (?, ?, ?)", productId, orgId, sku);
        UUID variantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.variant (id, organization_id, product_id, sku) VALUES (?, ?, ?, ?)",
                variantId, orgId, productId, sku);
        return variantId;
    }

    private OrderEventPayload orderEvent(String orderNumber, String sku, long sequence, OrderItemStatus targetStatus) {
        OrderEventPayload.OrderEventItem item = new OrderEventPayload.OrderEventItem(
                sku, sku, sku, null, 1, new BigDecimal("19.99"), BigDecimal.ZERO, targetStatus);
        return new OrderEventPayload(orgId, channelConnectionId, "evt-" + orderNumber + "-" + sequence, orderNumber,
                Instant.now(), sequence, new BigDecimal("19.99"), "USD", List.of(item), null);
    }
}
