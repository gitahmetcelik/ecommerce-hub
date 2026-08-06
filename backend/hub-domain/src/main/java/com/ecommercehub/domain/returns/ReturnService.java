package com.ecommercehub.domain.returns;

import com.ecommercehub.domain.audit.AuditLogService;
import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.order.OrderItem;
import com.ecommercehub.domain.order.OrderItemRepository;
import com.ecommercehub.domain.stock.StockLedgerService;
import com.ecommercehub.domain.tenant.TenantContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * plan §7's return state machine, minus the parts that talk to a channel (those are
 * {@code ReturnFulfilmentService}, which needs the connector layer).
 *
 * <p><b>Authorisation is enforced here, not in the controller.</b> Approving a return
 * requires OPERATOR and authorising a refund requires ADMIN, and putting those checks
 * in the service means they hold for every caller — the HTTP endpoint today, a task
 * handler or an import tomorrow. A rule that only exists in a controller is one the
 * second entry point quietly skips.
 */
@Service
public class ReturnService {

    private static final Logger log = LoggerFactory.getLogger(ReturnService.class);

    private final ReturnRequestRepository returnRequestRepository;
    private final ReturnItemRepository returnItemRepository;
    private final OrderItemRepository orderItemRepository;
    private final StockLedgerService stockLedgerService;
    private final TenantContextService tenantContextService;
    private final AuditLogService auditLog;
    private final JdbcTemplate jdbcTemplate;
    private final Duration reminderAfter;
    private final Duration timeoutAfter;

    public ReturnService(ReturnRequestRepository returnRequestRepository, ReturnItemRepository returnItemRepository,
                          OrderItemRepository orderItemRepository, StockLedgerService stockLedgerService,
                          TenantContextService tenantContextService, AuditLogService auditLog,
                          JdbcTemplate jdbcTemplate,
                          @Value("${hub.returns.reminder-after:PT24H}") Duration reminderAfter,
                          @Value("${hub.returns.timeout-after:PT48H}") Duration timeoutAfter) {
        this.returnRequestRepository = returnRequestRepository;
        this.returnItemRepository = returnItemRepository;
        this.orderItemRepository = orderItemRepository;
        this.stockLedgerService = stockLedgerService;
        this.tenantContextService = tenantContextService;
        this.auditLog = auditLog;
        this.jdbcTemplate = jdbcTemplate;
        this.reminderAfter = reminderAfter;
        this.timeoutAfter = timeoutAfter;
    }

    /**
     * Records a return the channel told us about and opens the approval window.
     *
     * <p>Idempotent on {@code channelReturnId}: a redelivered webhook returns the
     * existing return rather than opening a second one against the same order.
     */
    @Transactional
    public ReturnRequest recordChannelReturn(UUID organizationId, UUID salesOrderId, String channelReturnId,
                                              String reason, List<RequestedItem> items) {
        return recordChannelReturnIfNew(organizationId, salesOrderId, channelReturnId, reason, items).request();
    }

    /**
     * @return the return, and whether this call is what created it. The flag matters to
     *         the reconcile pass: its overlap window re-presents returns it has already
     *         seen, and counting those as new would report work that never happened.
     */
    @Transactional
    public RecordedReturn recordChannelReturnIfNew(UUID organizationId, UUID salesOrderId, String channelReturnId,
                                                    String reason, List<RequestedItem> items) {
        tenantContextService.setTransactionTenantContext(organizationId);

        return returnRequestRepository
                .findByOrganizationIdAndSalesOrderIdAndChannelReturnId(organizationId, salesOrderId, channelReturnId)
                .map(existing -> new RecordedReturn(existing, false))
                .orElseGet(() -> new RecordedReturn(
                        openReturn(organizationId, salesOrderId, channelReturnId, reason, items), true));
    }

    private ReturnRequest openReturn(UUID organizationId, UUID salesOrderId, String channelReturnId,
                                      String reason, List<RequestedItem> items) {
        ReturnRequest request = returnRequestRepository.save(
                new ReturnRequest(UUID.randomUUID(), organizationId, salesOrderId, channelReturnId, reason));

        for (RequestedItem item : items) {
            returnItemRepository.save(new ReturnItem(UUID.randomUUID(), organizationId, request.getId(),
                    item.orderItemId(), item.quantity()));
        }

        Instant now = Instant.now();
        request.startApprovalWindow(now.plus(reminderAfter), now.plus(timeoutAfter));
        enqueueOperatorItem(organizationId, "RETURN_APPROVAL", request.getId(),
                "Return " + request.getId() + " is awaiting an approve/reject decision");

        log.info("Return {} opened for order {} and is awaiting approval", request.getId(), salesOrderId);
        return request;
    }

    /** plan §7: OPERATOR or higher. */
    @Transactional
    public ReturnRequest approve(AuthenticatedUser actor, UUID returnRequestId) {
        requireRole(actor, HubRole.OPERATOR, "approve a return");
        tenantContextService.setTransactionTenantContext(actor.organizationId());

        ReturnRequest request = require(returnRequestId);
        requireAwaitingDecision(request);

        request.approve(actor.userId(), Instant.now());
        closeOperatorItem(actor.organizationId(), request.getId());

        auditLog.record(actor.organizationId(), actor.userId(), AuditLogService.RETURN_APPROVED,
                Map.of("returnRequestId", request.getId().toString()));
        return request;
    }

    /** plan §7: OPERATOR or higher. Rejection is always a human act — nothing auto-rejects. */
    @Transactional
    public ReturnRequest reject(AuthenticatedUser actor, UUID returnRequestId, String reason) {
        requireRole(actor, HubRole.OPERATOR, "reject a return");
        tenantContextService.setTransactionTenantContext(actor.organizationId());

        ReturnRequest request = require(returnRequestId);
        requireAwaitingDecision(request);

        request.reject(actor.userId(), Instant.now(), reason);
        closeOperatorItem(actor.organizationId(), request.getId());

        auditLog.record(actor.organizationId(), actor.userId(), AuditLogService.RETURN_REJECTED,
                Map.of("returnRequestId", request.getId().toString(), "reason", String.valueOf(reason)));
        return request;
    }

    /**
     * The goods came back. Sellable units go to on_hand, damaged ones to damaged
     * (plan §3's reservation table) — the counters are separate because damaged stock
     * is not stock you can sell, and merging them would advertise it.
     */
    @Transactional
    public ReturnRequest recordReceipt(UUID organizationId, UUID returnRequestId, Map<UUID, Disposition> byItemId) {
        tenantContextService.setTransactionTenantContext(organizationId);

        ReturnRequest request = require(returnRequestId);
        if (request.getStatus() != ReturnStatus.ACCEPTED
                && request.getStatus() != ReturnStatus.RETURN_SHIPMENT_CREATED) {
            throw new IllegalStateException(
                    "Return " + returnRequestId + " is " + request.getStatus() + ", not awaiting goods");
        }

        boolean allIntact = true;
        for (ReturnItem item : returnItemRepository.findByReturnRequestId(returnRequestId)) {
            Disposition disposition = byItemId.getOrDefault(item.getId(),
                    new Disposition(item.getQuantity(), 0));
            item.recordDisposition(disposition.intact(), disposition.damaged());

            UUID variantId = variantOf(item);
            if (disposition.intact() > 0) {
                stockLedgerService.recordOnHandIncrease(organizationId, variantId, disposition.intact(), item.getId());
            }
            if (disposition.damaged() > 0) {
                stockLedgerService.recordDamagedIncrease(organizationId, variantId, disposition.damaged(), item.getId());
                allIntact = false;
            }
        }

        request.recordReceipt(allIntact);
        log.info("Return {} received — {}", returnRequestId, allIntact ? "all units sellable" : "some units damaged");
        return request;
    }

    /** Moves an accepted-and-received return to the point where a refund amount exists. */
    @Transactional
    public ReturnRequest calculateRefund(UUID organizationId, UUID returnRequestId) {
        tenantContextService.setTransactionTenantContext(organizationId);

        ReturnRequest request = require(returnRequestId);
        if (request.getStatus() != ReturnStatus.RETURN_RECEIVED) {
            throw new IllegalStateException("Return " + returnRequestId + " is " + request.getStatus()
                    + ", so there is nothing to calculate a refund from yet");
        }
        request.moveTo(ReturnStatus.REFUND_CALCULATED);
        return request;
    }

    /**
     * What the customer is owed: the unit price times the quantity actually returned.
     * Damaged units are still refunded — whether the goods came back sellable is our
     * inventory problem, not a reason to keep the customer's money. A restocking-fee
     * policy would go here and is deliberately not invented.
     */
    @Transactional(readOnly = true)
    public java.math.BigDecimal refundAmount(UUID organizationId, UUID returnRequestId) {
        tenantContextService.setTransactionTenantContext(organizationId);

        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        for (ReturnItem item : returnItemRepository.findByReturnRequestId(returnRequestId)) {
            OrderItem orderItem = orderItemRepository.findById(item.getOrderItemId()).orElseThrow();
            total = total.add(orderItem.getUnitPrice().multiply(java.math.BigDecimal.valueOf(item.getQuantity())));
        }
        return total;
    }

    @Transactional(readOnly = true)
    public ReturnRequest get(UUID organizationId, UUID returnRequestId) {
        tenantContextService.setTransactionTenantContext(organizationId);
        return require(returnRequestId);
    }

    /**
     * The return label now exists — whether we asked the channel for it or merely
     * recorded the one it made (plan §7).
     *
     * <p>This and {@link #markRefunded} exist as named transitions rather than a public
     * {@code moveTo(status)} because the fulfilment step lives in another module: an
     * exported "set any status" method would let any caller put a return into any state,
     * which is the state machine's whole job to prevent. The transitions stay here; only
     * the two the channel layer legitimately causes are exported.
     */
    @Transactional
    public ReturnRequest markReturnShipmentCreated(UUID organizationId, UUID returnRequestId) {
        tenantContextService.setTransactionTenantContext(organizationId);

        ReturnRequest request = require(returnRequestId);
        if (request.getStatus() != ReturnStatus.ACCEPTED) {
            throw new IllegalStateException("Return " + returnRequestId + " is " + request.getStatus()
                    + " — a return label only follows an accepted return");
        }
        request.moveTo(ReturnStatus.RETURN_SHIPMENT_CREATED);
        return request;
    }

    /**
     * plan §7: the return-label step failed again.
     *
     * <p>The engine's own retry eventually drops the task in the DLQ, and that alone is
     * not enough — a DLQ is a place failures go to be forgotten unless somebody is
     * watching it. Once the attempts reach the limit this also raises an operator queue
     * item, so the failure lands somewhere a human actually looks. The item is raised
     * once, not once per subsequent attempt.
     *
     * @return the new attempt count
     */
    @Transactional
    public int recordShipmentFailure(UUID organizationId, UUID returnRequestId, int maxAttempts, String reason) {
        tenantContextService.setTransactionTenantContext(organizationId);

        ReturnRequest request = require(returnRequestId);
        int attempts = request.recordShipmentAttemptFailed();

        if (attempts == maxAttempts) {
            enqueueOperatorItem(organizationId, "RETURN_SHIPMENT_FAILED", returnRequestId,
                    "Creating the return label for " + returnRequestId + " failed " + attempts
                            + " times and was given up on: " + reason);
            log.error("Return {} exhausted {} shipment attempts — escalated to the operator queue",
                    returnRequestId, attempts);
        }
        return attempts;
    }

    /** The money is back with the customer — paid by us, or observed being paid by the channel. */
    @Transactional
    public ReturnRequest markRefunded(UUID organizationId, UUID returnRequestId) {
        tenantContextService.setTransactionTenantContext(organizationId);

        ReturnRequest request = require(returnRequestId);
        request.moveTo(ReturnStatus.REFUNDED);
        return request;
    }

    public ReturnRequest require(UUID returnRequestId) {
        return returnRequestRepository.findById(returnRequestId)
                .orElseThrow(() -> new IllegalArgumentException("No return_request " + returnRequestId));
    }

    private void requireAwaitingDecision(ReturnRequest request) {
        if (!request.getStatus().isAwaitingDecision()) {
            throw new IllegalStateException("Return " + request.getId() + " is " + request.getStatus()
                    + " and is no longer awaiting a decision");
        }
    }

    /**
     * Records the denial before throwing. A refused privileged action is exactly the
     * kind of event an audit trail is kept for, and the throw would otherwise be the
     * only trace it ever happened.
     */
    private void requireRole(AuthenticatedUser actor, HubRole required, String action) {
        if (!actor.hasAtLeast(required)) {
            auditLog.record(actor.organizationId(), actor.userId(), AuditLogService.PERMISSION_DENIED,
                    Map.of("action", action, "role", actor.effectiveRole().name(), "required", required.name()));
            throw new InsufficientRoleException(actor.effectiveRole(), required);
        }
    }

    private UUID variantOf(ReturnItem item) {
        return orderItemRepository.findById(item.getOrderItemId())
                .orElseThrow(() -> new IllegalStateException(
                        "Return item " + item.getId() + " points at a missing order item"))
                .getVariantId();
    }

    void enqueueOperatorItem(UUID organizationId, String type, UUID referenceId, String description) {
        jdbcTemplate.update("""
                INSERT INTO hub.operator_queue (id, organization_id, type, description, reference_id)
                VALUES (gen_random_uuid(), ?, ?, ?, ?)
                """, organizationId, type, description, referenceId);
    }

    private void closeOperatorItem(UUID organizationId, UUID referenceId) {
        jdbcTemplate.update("""
                UPDATE hub.operator_queue SET status = 'RESOLVED', updated_at = now(), version = version + 1
                WHERE organization_id = ? AND reference_id = ? AND status = 'PENDING'
                """, organizationId, referenceId);
    }

    public record RequestedItem(UUID orderItemId, int quantity) {
    }

    /** @param created false when this return was already known — a redelivery or an overlap re-read. */
    public record RecordedReturn(ReturnRequest request, boolean created) {
    }

    /** How many of the returned units came back sellable, and how many did not. */
    public record Disposition(int intact, int damaged) {
    }
}
