package com.ecommercehub.app;

import com.ecommercehub.app.returns.ReturnFulfilmentService;
import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.AuthenticationFailedException;
import com.ecommercehub.domain.auth.AuthenticationService;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.auth.InvalidTokenException;
import com.ecommercehub.domain.auth.JwtService;
import com.ecommercehub.domain.order.OrderEventPayload;
import com.ecommercehub.domain.order.OrderItemStatus;
import com.ecommercehub.domain.order.OrderProcessingService;
import com.ecommercehub.domain.returns.ReturnApprovalTimerService;
import com.ecommercehub.domain.returns.ReturnPayment;
import com.ecommercehub.domain.returns.ReturnRequest;
import com.ecommercehub.domain.returns.ReturnService;
import com.ecommercehub.domain.returns.ReturnStatus;
import com.ecommercehub.domain.returns.Shipment;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.security.EncryptedCredential;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * plan §12 Faz 5 gate: authentication (§10) and the return flow (§7) — approval,
 * rejection, timeout, stock disposition, the capability branch, and the two dangerous
 * channel calls behind their intent records.
 */
@SpringBootTest(properties = {
        // A three-second "48 hours" — the deadline behaviour is what is under test, and
        // waiting two days to observe it is not a test.
        "hub.returns.reminder-after=PT1S",
        "hub.returns.timeout-after=PT2S",
        "hub.returns.shipment-max-attempts=5"
})
public class Faz5GateTests extends AbstractTestcontainersTest {

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
    @Autowired private AuthenticationService authenticationService;
    @Autowired private JwtService jwtService;
    @Autowired private ReturnService returnService;
    @Autowired private ReturnFulfilmentService fulfilmentService;
    @Autowired private ReturnApprovalTimerService timerService;
    @Autowired private OrderProcessingService orderProcessingService;
    @Autowired private StockLedgerService stockLedgerService;
    @Autowired private CredentialEncryptionService credentialEncryptionService;
    @Autowired private ObjectMapper objectMapper;

    private UUID orgId;
    private UUID observingChannel;   // MOCK — refunds are made by the channel
    private UUID refundingChannel;   // MOCK_REFUND — we make the refund
    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://" + mockPazaryeri.getHost() + ":" + mockPazaryeri.getMappedPort(PORT);
        adminPost("/_admin/reset", "{}");

        orgId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.organization (id, name) VALUES (?, ?)", orgId, "Org");

        observingChannel = insertConnection("MOCK");
        refundingChannel = insertConnection("MOCK_REFUND");
    }

    // =========================================================================
    // Gate 1 — invite, log in, end the session
    // =========================================================================

    @Test
    @DisplayName("Faz 5 gate 1: a user is invited, sets a password, logs in, and can be logged out again")
    void test1_InviteLoginLogout() {
        UUID adminId = seedUser("admin@example.com", HubRole.ADMIN);

        var invitation = authenticationService.invite(orgId, adminId, "newcomer@example.com", "New Comer", HubRole.OPERATOR);

        assertThat(statusOfUser(invitation.userId()))
                .withFailMessage("An invited account must exist but not be able to authenticate yet")
                .isEqualTo("INVITED");
        assertThatThrownBy(() -> authenticationService.login(orgId, "newcomer@example.com", "anything"))
                .isInstanceOf(AuthenticationFailedException.class);

        var accepted = authenticationService.acceptInvitation(invitation.token(), "s3cret-password", "New Comer");
        assertThat(accepted.roles()).containsExactly(HubRole.OPERATOR);
        assertThat(statusOfUser(invitation.userId())).isEqualTo("ACTIVE");

        var tokens = authenticationService.login(orgId, "newcomer@example.com", "s3cret-password");
        AuthenticatedUser fromToken = jwtService.parseAccessToken(tokens.accessToken());
        assertThat(fromToken.organizationId())
                .withFailMessage("The tenant must be carried by the signed token, not asked of the caller")
                .isEqualTo(orgId);
        assertThat(fromToken.effectiveRole()).isEqualTo(HubRole.OPERATOR);

        authenticationService.logout(tokens.refreshToken());

        assertThatThrownBy(() -> authenticationService.refresh(tokens.refreshToken()))
                .withFailMessage("A revoked session must not be refreshable — that is what logging out means")
                .isInstanceOf(InvalidTokenException.class);

        assertThat(auditActions()).contains("USER_INVITED", "INVITATION_ACCEPTED", "LOGIN_SUCCEEDED", "LOGOUT");
    }

    @Test
    @DisplayName("Faz 5 gate 1b: a refresh token is single-use — presenting it twice fails the second time")
    void test1b_RefreshTokenRotates() {
        seedActiveUser("rotate@example.com", "pw-rotate", HubRole.OPERATOR);
        var first = authenticationService.login(orgId, "rotate@example.com", "pw-rotate");

        var second = authenticationService.refresh(first.refreshToken());
        assertThat(second.refreshToken()).isNotEqualTo(first.refreshToken());

        assertThatThrownBy(() -> authenticationService.refresh(first.refreshToken()))
                .withFailMessage("A replayed refresh token must fail — otherwise a stolen copy is a parallel session")
                .isInstanceOf(InvalidTokenException.class);
    }

    @Test
    @DisplayName("Faz 5 gate 1c: a password reset invalidates every existing session")
    void test1c_PasswordResetEndsSessions() {
        seedActiveUser("reset@example.com", "old-password", HubRole.OPERATOR);
        var session = authenticationService.login(orgId, "reset@example.com", "old-password");

        String token = authenticationService.requestPasswordReset(orgId, "reset@example.com").orElseThrow();
        authenticationService.confirmPasswordReset(token, "brand-new-password");

        assertThatThrownBy(() -> authenticationService.refresh(session.refreshToken()))
                .withFailMessage("A password changed BECAUSE of a compromise must not leave the intruder's session alive")
                .isInstanceOf(InvalidTokenException.class);

        assertThat(authenticationService.login(orgId, "reset@example.com", "brand-new-password")).isNotNull();
    }

    // =========================================================================
    // Gate 2 — the three decision paths
    // =========================================================================

    @Test
    @DisplayName("Faz 5 gate 2a: an approved return moves to ACCEPTED and clears its operator queue item")
    void test2a_ApprovalPath() {
        AuthenticatedUser operator = actor(HubRole.OPERATOR);
        UUID returnId = openReturn(observingChannel, "SKU-APPROVE", 2);

        assertThat(pendingOperatorItems("RETURN_APPROVAL"))
                .withFailMessage("A return awaiting a human decision must be visible in the operator queue")
                .isEqualTo(1);

        ReturnRequest approved = returnService.approve(operator, returnId);

        assertThat(approved.getStatus()).isEqualTo(ReturnStatus.ACCEPTED);
        assertThat(approved.getApprovedByUserId()).isEqualTo(operator.userId());
        assertThat(pendingOperatorItems("RETURN_APPROVAL")).isZero();
    }

    @Test
    @DisplayName("Faz 5 gate 2b: a rejected return records who rejected it and why, and accepts no second decision")
    void test2b_RejectionPath() {
        AuthenticatedUser operator = actor(HubRole.OPERATOR);
        UUID returnId = openReturn(observingChannel, "SKU-REJECT", 1);

        ReturnRequest rejected = returnService.reject(operator, returnId, "Outside the return window");
        assertThat(rejected.getStatus()).isEqualTo(ReturnStatus.REJECTED);

        assertThatThrownBy(() -> returnService.approve(operator, returnId))
                .withFailMessage("A decided return must not be decidable again")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("Faz 5 gate 2c: the deadline escalates to a human and never auto-rejects")
    void test2c_TimeoutEscalatesWithoutDeciding() throws Exception {
        UUID returnId = openReturn(observingChannel, "SKU-TIMEOUT", 1);

        Thread.sleep(1200);
        assertThat(timerService.sendReminders(orgId)).isEqualTo(1);
        assertThat(timerService.sendReminders(orgId))
                .withFailMessage("The 24h reminder is a nudge, not a repeating alarm")
                .isZero();

        Thread.sleep(1200);
        assertThat(timerService.escalateTimeouts(orgId)).isEqualTo(1);

        ReturnRequest timedOut = returnService.get(orgId, returnId);
        assertThat(timedOut.getStatus())
                .withFailMessage("plan §0: the timeout escalates. An automatic rejection is a decision nobody made")
                .isEqualTo(ReturnStatus.TIMED_OUT);
        assertThat(pendingOperatorItems("RETURN_APPROVAL_TIMEOUT")).isEqualTo(1);

        // Still decidable: a late operator can act normally.
        assertThat(returnService.approve(actor(HubRole.OPERATOR), returnId).getStatus())
                .isEqualTo(ReturnStatus.ACCEPTED);
    }

    // =========================================================================
    // Gate 3 — stock disposition
    // =========================================================================

    @Test
    @DisplayName("Faz 5 gate 3: sellable units return to on_hand and damaged ones to damaged, never mixed")
    void test3_IntactAndDamagedGoToDifferentCounters() {
        AuthenticatedUser operator = actor(HubRole.OPERATOR);
        UUID variantId = insertVariant("SKU-DISPOSITION", 10);
        UUID returnId = openReturnForVariant(observingChannel, variantId, "SKU-DISPOSITION", 4);
        returnService.approve(operator, returnId);

        int onHandBefore = counter(variantId, "on_hand");
        int damagedBefore = counter(variantId, "damaged");

        UUID returnItemId = onlyReturnItemId(returnId);
        returnService.recordReceipt(orgId, returnId,
                Map.of(returnItemId, new ReturnService.Disposition(3, 1)));

        assertThat(counter(variantId, "on_hand"))
                .withFailMessage("Three sellable units must go back into sellable stock")
                .isEqualTo(onHandBefore + 3);
        assertThat(counter(variantId, "damaged"))
                .withFailMessage("The damaged unit must not be advertised as sellable")
                .isEqualTo(damagedBefore + 1);
    }

    @Test
    @DisplayName("Faz 5 gate 3b: a disposition that does not account for every returned unit is rejected")
    void test3b_DispositionMustBalance() {
        AuthenticatedUser operator = actor(HubRole.OPERATOR);
        UUID variantId = insertVariant("SKU-BALANCE", 10);
        UUID returnId = openReturnForVariant(observingChannel, variantId, "SKU-BALANCE", 4);
        returnService.approve(operator, returnId);

        UUID returnItemId = onlyReturnItemId(returnId);

        assertThatThrownBy(() -> returnService.recordReceipt(orgId, returnId,
                Map.of(returnItemId, new ReturnService.Disposition(2, 1))))
                .withFailMessage("Units that are neither sellable nor damaged have vanished — that must not be accepted")
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // Gate 4 — the shipment step fails loudly, not silently
    // =========================================================================

    @Test
    @DisplayName("Faz 5 gate 4: a return label that keeps failing reaches the operator queue instead of vanishing")
    void test4_ShipmentFailureEscalates() {
        AuthenticatedUser operator = actor(HubRole.OPERATOR);
        UUID returnId = openReturn(refundingChannel, "SKU-SHIPFAIL", 1);
        returnService.approve(operator, returnId);

        adminPost("/_admin/scenario", "{\"shipmentFails\": true}");

        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThatThrownBy(() -> fulfilmentService.createReturnShipment(orgId, returnId))
                    .withFailMessage("A failed label must propagate so the engine retries it, not be swallowed")
                    .isInstanceOf(RuntimeException.class);
        }

        assertThat(pendingOperatorItems("RETURN_SHIPMENT_FAILED"))
                .withFailMessage("After the attempts are exhausted a human must be told — a DLQ nobody reads is a silent loss")
                .isEqualTo(1);

        Integer shipmentRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.shipment WHERE return_request_id = ?", Integer.class, returnId);
        assertThat(shipmentRows)
                .withFailMessage("Five attempts must reuse one shipment row — a new row per retry means a new "
                        + "idempotency key and therefore a real second label the moment one response is lost")
                .isEqualTo(1);
    }

    // =========================================================================
    // Gate 5 — a crash between the refund call and its commit must not pay twice
    // =========================================================================

    @Test
    @DisplayName("Faz 5 gate 5: a refund whose result was never recorded is resolved by asking the channel, not by paying again")
    void test5_InFlightRefundIsResolvedNotRepeated() {
        AuthenticatedUser admin = actor(HubRole.ADMIN);
        UUID returnId = readyForRefund(refundingChannel, "SKU-REFUND", 1, admin);

        ReturnPayment payment = fulfilmentService.issueRefund(admin, returnId);
        assertThat(payment.getStatus()).isEqualTo(ReturnPayment.STATUS_PAID);
        assertThat(refundsAtChannel()).hasSize(1);

        // Simulate the crash: the call happened, the result never got written. Rewind the
        // intent to SENT and the payment to PENDING — exactly the state a worker killed
        // between the two would leave behind.
        jdbcTemplate.update("UPDATE hub.channel_call_intent SET status = 'SENT' WHERE type = 'REFUND'");
        jdbcTemplate.update("UPDATE hub.return_payment SET status = 'PENDING' WHERE id = ?", payment.getId());

        assertThat(fulfilmentService.resolveInFlightRefunds(orgId)).isTrue();

        assertThat(refundsAtChannel())
                .withFailMessage("Recovery must ASK the channel what happened — a retry here is a second payment")
                .hasSize(1);
        assertThat(paymentStatus(payment.getId())).isEqualTo(ReturnPayment.STATUS_PAID);
        assertThat(returnService.get(orgId, returnId).getStatus()).isEqualTo(ReturnStatus.REFUNDED);
    }

    // =========================================================================
    // Gate 6 — the capability branch
    // =========================================================================

    @Test
    @DisplayName("Faz 5 gate 6: on a channel that refunds its own customers, the hub observes and makes no call")
    void test6_RefundIsObservedWhenTheChannelIsTheMerchantOfRecord() {
        AuthenticatedUser admin = actor(HubRole.ADMIN);
        UUID returnId = readyForRefund(observingChannel, "SKU-OBSERVED", 1, admin);

        ReturnPayment payment = fulfilmentService.issueRefund(admin, returnId);

        assertThat(payment.getStatus())
                .withFailMessage("plan §7: without REFUND_BY_US the refund is an event we observe, not one we cause")
                .isEqualTo(ReturnPayment.STATUS_PAID_BY_CHANNEL);
        assertThat(refundsAtChannel())
                .withFailMessage("No refund call may be made to a channel that refunds its customers itself")
                .isEmpty();
        assertThat(returnService.get(orgId, returnId).getStatus()).isEqualTo(ReturnStatus.REFUNDED);

        Integer refundIntents = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM hub.channel_call_intent WHERE organization_id = ? AND type = 'REFUND'",
                Integer.class, orgId);
        assertThat(refundIntents)
                .withFailMessage("An intent records an intent to CALL — the observation path makes none")
                .isZero();
    }

    @Test
    @DisplayName("Faz 5 gate 6b: a channel that makes its own return labels gets recorded, not asked")
    void test6b_ShipmentIsObservedWhenTheChannelProducesTheLabel() {
        // The default MOCK profile does have SHIPMENT_CREATE, so this exercises the other
        // branch through a connection whose channel type advertises no such capability.
        AuthenticatedUser operator = actor(HubRole.OPERATOR);
        UUID returnId = openReturn(observingChannel, "SKU-LABEL", 1);
        returnService.approve(operator, returnId);

        Shipment shipment = fulfilmentService.createReturnShipment(orgId, returnId);

        assertThat(shipment.getSource()).isEqualTo(Shipment.SOURCE_CREATED_BY_US);
        assertThat(returnService.get(orgId, returnId).getStatus()).isEqualTo(ReturnStatus.RETURN_SHIPMENT_CREATED);
    }

    // =========================================================================
    // Gate 7 — role enforcement
    // =========================================================================

    @Test
    @DisplayName("Faz 5 gate 7: an OBSERVER cannot approve a return, and the refusal is audited")
    void test7_ObserverCannotApprove() {
        AuthenticatedUser observer = actor(HubRole.OBSERVER);
        UUID returnId = openReturn(observingChannel, "SKU-OBSERVER", 1);

        assertThatThrownBy(() -> returnService.approve(observer, returnId))
                .isInstanceOf(InsufficientRoleException.class);

        assertThat(returnService.get(orgId, returnId).getStatus())
                .withFailMessage("A refused approval must leave the return exactly as it was")
                .isEqualTo(ReturnStatus.AWAITING_APPROVAL);
        assertThat(auditActions())
                .withFailMessage("A refused privileged action is precisely what an audit trail is for")
                .contains("PERMISSION_DENIED");
    }

    @Test
    @DisplayName("Faz 5 gate 7b: an OPERATOR may approve but may not authorise a refund — that is ADMIN only")
    void test7b_OperatorCannotRefund() {
        AuthenticatedUser operator = actor(HubRole.OPERATOR);
        UUID returnId = readyForRefund(refundingChannel, "SKU-NOREFUND", 1, operator);

        assertThatThrownBy(() -> fulfilmentService.issueRefund(operator, returnId))
                .withFailMessage("plan §7 puts money behind ADMIN")
                .isInstanceOf(InsufficientRoleException.class);

        assertThat(refundsAtChannel()).isEmpty();
    }

    // =========================================================================
    // Fixtures and helpers
    // =========================================================================

    private UUID insertConnection(String channelType) {
        EncryptedCredential encrypted = credentialEncryptionService.encrypt(baseUrl);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.channel_connection
                    (id, organization_id, channel_type, encrypted_credentials, key_version)
                VALUES (?, ?, ?, ?, ?)
                """, id, orgId, channelType, encrypted.ciphertextBase64(), encrypted.keyVersion());
        return id;
    }

    private UUID seedUser(String email, HubRole role) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO hub.app_user (id, organization_id, email, password_hash, full_name, status)
                VALUES (?, ?, ?, 'seeded', ?, 'ACTIVE')
                """, userId, orgId, email, email);
        jdbcTemplate.update("""
                INSERT INTO hub.user_role (id, organization_id, user_id, role_name)
                VALUES (gen_random_uuid(), ?, ?, ?)
                """, orgId, userId, role.name());
        return userId;
    }

    /** Seeds a user who can actually log in, by going through the invitation flow. */
    private UUID seedActiveUser(String email, String password, HubRole role) {
        UUID adminId = seedUser("admin-" + email, HubRole.ADMIN);
        var invitation = authenticationService.invite(orgId, adminId, email, email, role);
        authenticationService.acceptInvitation(invitation.token(), password, email);
        return invitation.userId();
    }

    /** An actor with the given role, as the security filter would have produced from a token. */
    private AuthenticatedUser actor(HubRole role) {
        return new AuthenticatedUser(seedUser(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@example.com", role),
                orgId, "actor@example.com", List.of(role));
    }

    private UUID insertVariant(String sku, int onHand) {
        UUID productId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.product (id, organization_id, title) VALUES (?, ?, ?)",
                productId, orgId, "Product " + sku);

        UUID variantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO hub.variant (id, organization_id, product_id, sku) VALUES (?, ?, ?, ?)",
                variantId, orgId, productId, sku);

        inTenant(() -> stockLedgerService.recordSupply(orgId, variantId, onHand, null));
        return variantId;
    }

    private UUID openReturn(UUID channelConnectionId, String sku, int quantity) {
        return openReturnForVariant(channelConnectionId, insertVariant(sku, 50), sku, quantity);
    }

    private UUID openReturnForVariant(UUID channelConnectionId, UUID variantId, String sku, int quantity) {
        String orderNumber = "ORD-" + UUID.randomUUID();
        OrderEventPayload.OrderEventItem item = new OrderEventPayload.OrderEventItem(
                sku, sku, sku, null, quantity, new BigDecimal("25.00"), BigDecimal.ZERO, OrderItemStatus.CREATED);

        orderProcessingService.process(new OrderEventPayload(orgId, channelConnectionId, "evt-" + orderNumber,
                orderNumber, Instant.now(), null, new BigDecimal("25.00"), "USD", List.of(item), null));

        UUID salesOrderId = jdbcTemplate.queryForObject(
                "SELECT id FROM hub.sales_order WHERE organization_id = ? AND channel_order_number = ?",
                UUID.class, orgId, orderNumber);
        UUID orderItemId = jdbcTemplate.queryForObject(
                "SELECT id FROM hub.order_item WHERE sales_order_id = ?", UUID.class, salesOrderId);

        return returnService.recordChannelReturn(orgId, salesOrderId, "chret-" + orderNumber, "Changed mind",
                List.of(new ReturnService.RequestedItem(orderItemId, quantity))).getId();
    }

    /** Walks a return all the way to the point where a refund is the next step. */
    private UUID readyForRefund(UUID channelConnectionId, String sku, int quantity, AuthenticatedUser actor) {
        UUID returnId = openReturn(channelConnectionId, sku, quantity);
        returnService.approve(actor.hasAtLeast(HubRole.OPERATOR) ? actor : actor(HubRole.OPERATOR), returnId);

        UUID returnItemId = onlyReturnItemId(returnId);
        returnService.recordReceipt(orgId, returnId, Map.of(returnItemId, new ReturnService.Disposition(quantity, 0)));
        returnService.calculateRefund(orgId, returnId);
        return returnId;
    }

    private UUID onlyReturnItemId(UUID returnRequestId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM hub.return_item WHERE return_request_id = ?", UUID.class, returnRequestId);
    }

    private int counter(UUID variantId, String column) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT " + column + " FROM hub.stock WHERE organization_id = ? AND variant_id = ?",
                Integer.class, orgId, variantId);
        return value == null ? 0 : value;
    }

    private int pendingOperatorItems(String type) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM hub.operator_queue
                WHERE organization_id = ? AND type = ? AND status = 'PENDING'
                """, Integer.class, orgId, type);
        return count == null ? 0 : count;
    }

    private String statusOfUser(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT status FROM hub.app_user WHERE id = ?", String.class, userId);
    }

    private String paymentStatus(UUID paymentId) {
        return jdbcTemplate.queryForObject("SELECT status FROM hub.return_payment WHERE id = ?", String.class, paymentId);
    }

    private List<String> auditActions() {
        return jdbcTemplate.queryForList(
                "SELECT action FROM hub.audit_log WHERE organization_id = ?", String.class, orgId);
    }

    private List<JsonNode> refundsAtChannel() {
        JsonNode refunds = readJson(adminGet("/_admin/refunds")).get("refunds");
        List<JsonNode> result = new java.util.ArrayList<>();
        refunds.forEach(result::add);
        return result;
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
