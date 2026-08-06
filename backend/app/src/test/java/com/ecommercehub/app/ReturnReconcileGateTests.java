package com.ecommercehub.app;

import com.ecommercehub.app.reconcile.ReconcileService;
import com.ecommercehub.domain.order.OrderEventPayload;
import com.ecommercehub.domain.order.OrderItemStatus;
import com.ecommercehub.domain.order.OrderProcessingService;
import com.ecommercehub.domain.returns.ReturnStatus;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.security.EncryptedCredential;
import com.ecommercehub.domain.stock.StockLedgerService;
import com.ecommercehub.domain.tenant.TenantContextService;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan §11 row 2: the hourly return delta pass — the only way a customer return
 * actually enters the hub.
 *
 * <p>mock-pazaryeri seeds five returns against its first five orders, each carrying the
 * same line items as the order it came from, so these tests exercise the real
 * order-and-line lookup rather than a hand-built fixture.
 */
@SpringBootTest
public class ReturnReconcileGateTests extends AbstractTestcontainersTest {

    private static final int PORT = 4100;
    private static final Path MOCK_PAZARYERI_DIR = Paths.get("../../mock-pazaryeri").toAbsolutePath().normalize();

    private static final GenericContainer<?> mockPazaryeri =
            new GenericContainer<>(new ImageFromDockerfile().withFileFromPath(".", MOCK_PAZARYERI_DIR))
                    .withExposedPorts(PORT);

    static {
        mockPazaryeri.start();
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private TenantContextService tenantContextService;
    @Autowired private ReconcileService reconcileService;
    @Autowired private OrderProcessingService orderProcessingService;
    @Autowired private StockLedgerService stockLedgerService;
    @Autowired private CredentialEncryptionService credentialEncryptionService;

    private UUID orgId;
    private UUID channelConnectionId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");

        String baseUrl = "http://" + mockPazaryeri.getHost() + ":" + mockPazaryeri.getMappedPort(PORT);
        EncryptedCredential encrypted = credentialEncryptionService.encrypt(baseUrl);
        channelConnectionId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection (id, organization_id, channel_type, encrypted_credentials, key_version)
                VALUES (?, ?, 'MOCK', ?, ?)
                """, channelConnectionId, orgId, encrypted.ciphertextBase64(), encrypted.keyVersion());
    }

    @Test
    @DisplayName("Return reconcile: a channel return becomes a hub return awaiting approval, with its real line items")
    void channelReturnsBecomeApprovalRequests() {
        seedOrder("order-0", "SKU-0", 1);

        int opened = reconcileService.reconcileReturns(orgId, channelConnectionId);
        assertThat(opened)
                .withFailMessage("The one return whose order we know must be opened")
                .isEqualTo(1);

        var row = jdbcTemplate.queryForMap("""
                SELECT status, channel_return_id FROM hub.return_request WHERE organization_id = ?
                """, orgId);
        assertThat(row.get("status")).isEqualTo(ReturnStatus.AWAITING_APPROVAL.name());
        assertThat(row.get("channel_return_id")).isEqualTo("return-0");

        Integer items = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.return_item WHERE organization_id = ?", Integer.class, orgId);
        assertThat(items)
                .withFailMessage("The return must carry the line that actually came back, not an empty shell")
                .isEqualTo(1);

        Integer awaiting = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM hub.operator_queue
                WHERE organization_id = ? AND type = 'RETURN_APPROVAL' AND status = 'PENDING'
                """, Integer.class, orgId);
        assertThat(awaiting).isEqualTo(1);
    }

    @Test
    @DisplayName("Return reconcile: the overlap window re-reads known returns without opening them twice")
    void repeatedReconcileIsIdempotent() {
        seedOrder("order-0", "SKU-0", 1);

        assertThat(reconcileService.reconcileReturns(orgId, channelConnectionId)).isEqualTo(1);
        assertThat(reconcileService.reconcileReturns(orgId, channelConnectionId))
                .withFailMessage("Plan §8's 5-minute overlap re-presents returns we already have — a second "
                        + "approval request for the same parcel would be a duplicate refund waiting to happen")
                .isZero();

        Integer returns = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.return_request WHERE organization_id = ?", Integer.class, orgId);
        assertThat(returns).isEqualTo(1);
    }

    @Test
    @DisplayName("Return reconcile: a return whose order we do not have is escalated, never dropped")
    void unresolvableReturnsReachTheOperatorQueue() {
        // No orders seeded at all — every one of the five seeded returns is unattachable.
        assertThat(reconcileService.reconcileReturns(orgId, channelConnectionId)).isZero();

        Integer returns = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.return_request WHERE organization_id = ?", Integer.class, orgId);
        assertThat(returns).isZero();

        Integer escalations = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM hub.operator_queue
                WHERE organization_id = ? AND type = 'RETURN_UNRESOLVABLE' AND status = 'PENDING'
                """, Integer.class, orgId);
        assertThat(escalations)
                .withFailMessage("A dropped return is a customer waiting for a refund nobody knows is owed")
                .isEqualTo(5);
    }

    @Test
    @DisplayName("Return reconcile: an escalation is raised once per return, not once per sweep")
    void escalationsAreNotRepeatedEverySweep() {
        reconcileService.reconcileReturns(orgId, channelConnectionId);
        reconcileService.reconcileReturns(orgId, channelConnectionId);

        Integer escalations = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM hub.operator_queue
                WHERE organization_id = ? AND type = 'RETURN_UNRESOLVABLE' AND status = 'PENDING'
                """, Integer.class, orgId);
        assertThat(escalations)
                .withFailMessage("An hourly sweep that re-raises the same escalation buries the queue it is meant to fill")
                .isEqualTo(5);
    }

    /** Creates the hub-side order that a seeded channel return refers to. */
    private void seedOrder(String channelOrderNumber, String sku, int quantity) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.product (id, organization_id, title) VALUES (?, ?, ?)",
                productId, orgId, "Product " + sku);
        jdbcTemplate.update("INSERT INTO hub.variant (id, organization_id, product_id, sku) VALUES (?, ?, ?, ?)",
                variantId, orgId, productId, sku);

        transactionTemplate.executeWithoutResult(status -> {
            tenantContextService.setTransactionTenantContext(orgId);
            stockLedgerService.recordSupply(orgId, variantId, 50, null);
        });

        OrderEventPayload.OrderEventItem item = new OrderEventPayload.OrderEventItem(
                sku, sku, sku, null, quantity, new BigDecimal("19.99"), BigDecimal.ZERO, OrderItemStatus.CREATED);

        orderProcessingService.process(new OrderEventPayload(orgId, channelConnectionId,
                "evt-" + channelOrderNumber, channelOrderNumber, Instant.now(), null,
                new BigDecimal("19.99"), "USD", List.of(item), null));
    }
}
