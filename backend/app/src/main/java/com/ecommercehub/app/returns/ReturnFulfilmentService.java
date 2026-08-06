package com.ecommercehub.app.returns;

import com.ecommercehub.connector.CallIntentRef;
import com.ecommercehub.connector.CallStatus;
import com.ecommercehub.connector.Capability;
import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.connector.PlatformConnector;
import com.ecommercehub.connector.RefundRequest;
import com.ecommercehub.connector.RefundResult;
import com.ecommercehub.connector.ShipmentRequest;
import com.ecommercehub.connector.ShipmentResult;
import com.ecommercehub.domain.audit.AuditLogService;
import com.ecommercehub.domain.auth.AuthenticatedUser;
import com.ecommercehub.domain.auth.HubRole;
import com.ecommercehub.domain.auth.InsufficientRoleException;
import com.ecommercehub.domain.channel.ChannelConnection;
import com.ecommercehub.domain.channel.ChannelConnectionRepository;
import com.ecommercehub.domain.intent.ChannelCallIntent;
import com.ecommercehub.domain.intent.ChannelCallIntentService;
import com.ecommercehub.domain.order.SalesOrder;
import com.ecommercehub.domain.order.SalesOrderRepository;
import com.ecommercehub.domain.returns.ReturnPayment;
import com.ecommercehub.domain.returns.ReturnPaymentRepository;
import com.ecommercehub.domain.returns.ReturnRequest;
import com.ecommercehub.domain.returns.ReturnRequestRepository;
import com.ecommercehub.domain.returns.ReturnService;
import com.ecommercehub.domain.returns.ReturnStatus;
import com.ecommercehub.domain.returns.Shipment;
import com.ecommercehub.domain.returns.ShipmentRepository;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.tenant.TenantContextService;
import com.ecommercehub.ingest.ConnectorRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The two steps of plan §7 that reach a channel: creating the return label and paying
 * the refund.
 *
 * <p><b>Both branch on the capability matrix, and neither knows which channel it is
 * talking to.</b> If the channel produces its own return label, RETURN_SHIPMENT_CREATED
 * is something we record rather than cause. If the channel is the merchant of record,
 * the refund is likewise observed and no call is made. plan §7 is explicit that the
 * state machine must not have channel types baked into it — it reads
 * {@link Capability}, and every channel takes the same path through these two methods.
 *
 * <p><b>Both go through {@link ChannelCallIntentService}</b>, in the plan §4.3 order:
 * the domain row (shipment / return_payment) commits first, then a PREPARED intent
 * pointing at it, then SENT commits <em>before</em> the call leaves. A crash between
 * the call and the response therefore leaves a SENT intent, and recovery asks the
 * channel what happened rather than repeating the call. For a refund that difference
 * is a second payment to a customer.
 */
@Service
public class ReturnFulfilmentService {

    private static final Logger log = LoggerFactory.getLogger(ReturnFulfilmentService.class);

    static final String INTENT_SHIPMENT = "SHIPMENT_CREATE";
    static final String INTENT_REFUND = "REFUND";

    private final ReturnService returnService;
    private final ReturnRequestRepository returnRequestRepository;
    private final ReturnPaymentRepository returnPaymentRepository;
    private final ShipmentRepository shipmentRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final ChannelConnectionRepository channelConnectionRepository;
    private final ChannelCallIntentService intentService;
    private final ConnectorRegistry connectorRegistry;
    private final CredentialEncryptionService credentialEncryptionService;
    private final TenantContextService tenantContextService;
    private final TransactionTemplate transactionTemplate;
    private final AuditLogService auditLog;
    private final ObjectMapper objectMapper;
    private final int shipmentMaxAttempts;

    public ReturnFulfilmentService(ReturnService returnService,
                                    ReturnRequestRepository returnRequestRepository,
                                    ReturnPaymentRepository returnPaymentRepository,
                                    ShipmentRepository shipmentRepository,
                                    SalesOrderRepository salesOrderRepository,
                                    ChannelConnectionRepository channelConnectionRepository,
                                    ChannelCallIntentService intentService,
                                    ConnectorRegistry connectorRegistry,
                                    CredentialEncryptionService credentialEncryptionService,
                                    TenantContextService tenantContextService,
                                    TransactionTemplate transactionTemplate,
                                    AuditLogService auditLog,
                                    ObjectMapper objectMapper,
                                    @org.springframework.beans.factory.annotation.Value("${hub.returns.shipment-max-attempts:5}")
                                    int shipmentMaxAttempts) {
        this.returnService = returnService;
        this.returnRequestRepository = returnRequestRepository;
        this.returnPaymentRepository = returnPaymentRepository;
        this.shipmentRepository = shipmentRepository;
        this.salesOrderRepository = salesOrderRepository;
        this.channelConnectionRepository = channelConnectionRepository;
        this.intentService = intentService;
        this.connectorRegistry = connectorRegistry;
        this.credentialEncryptionService = credentialEncryptionService;
        this.tenantContextService = tenantContextService;
        this.transactionTemplate = transactionTemplate;
        this.auditLog = auditLog;
        this.objectMapper = objectMapper;
        this.shipmentMaxAttempts = shipmentMaxAttempts;
    }

    /**
     * Creates the return label, or records the one the channel made.
     *
     * @return the shipment row either way — the caller does not need to know which
     *         branch ran, which is the point of the capability matrix
     */
    public Shipment createReturnShipment(UUID organizationId, UUID returnRequestId) {
        Context context = loadContext(organizationId, returnRequestId);

        if (!context.connector().capabilities().contains(Capability.SHIPMENT_CREATE)) {
            return observeChannelShipment(organizationId, context);
        }

        // plan §3: the domain row commits first, so the intent's UNIQUE(org, type,
        // target_reference) lands on a shipment that already exists. Doing it the other
        // way round would key the intent on nothing.
        //
        // Reused across retries rather than recreated. A fresh shipment row per attempt
        // would mean a fresh target_reference, a fresh intent, a fresh idempotency key —
        // and therefore a genuinely new label at the channel every time the previous
        // attempt's *response* was lost. That is the double-shipment this whole intent
        // mechanism exists to prevent, reintroduced through the back door.
        Shipment shipment = inTransaction(organizationId, () -> findOrCreatePendingShipment(
                organizationId, context.order().getId(), returnRequestId));

        ChannelCallIntent intent = inTransaction(organizationId, () -> findOrPrepareIntent(
                organizationId, context.connection().getId(), shipment.getId(), returnRequestId));

        // markSentIfPrepared, not markSent: on a retry this intent is already SENT, and
        // re-issuing the same call with the same idempotency key is exactly what should
        // happen next.
        inTransaction(organizationId, () -> {
            intentService.markSentIfPrepared(intent.getId());
            return null;
        });

        ShipmentResult result;
        try {
            result = context.connector().createShipment(context.ref(),
                    new ShipmentRequest(context.order().getChannelOrderNumber()),
                    new CallIntentRef(intent.getId(), intent.getId().toString()));
        } catch (RuntimeException e) {
            // Counted and escalated in its own transaction, then rethrown so the engine's
            // retry (and eventually its DLQ) still happens. Swallowing it here would turn
            // "the label was never created" into a silent success, which is the exact
            // failure plan §7 calls out.
            inTransaction(organizationId, () -> returnService.recordShipmentFailure(
                    organizationId, returnRequestId, shipmentMaxAttempts, String.valueOf(e.getMessage())));
            throw e;
        }

        ShipmentResult confirmed = result;
        return inTransaction(organizationId, () -> {
            Shipment persisted = shipmentRepository.findById(shipment.getId()).orElseThrow();
            persisted.recordChannelResult(confirmed.channelShipmentId(), confirmed.trackingNumber());
            intentService.recordResult(intent.getId(), toJson(Map.of("shipmentId", confirmed.channelShipmentId())));

            returnService.markReturnShipmentCreated(organizationId, returnRequestId);
            return persisted;
        });
    }

    /**
     * The label we are still trying to create, or a new one. "Still trying" means a
     * return-direction shipment we own that has no channel identifier yet.
     */
    private Shipment findOrCreatePendingShipment(UUID organizationId, UUID salesOrderId, UUID returnRequestId) {
        return shipmentRepository.findByReturnRequestId(returnRequestId).stream()
                .filter(s -> Shipment.SOURCE_CREATED_BY_US.equals(s.getSource()) && s.getTrackingNumber() == null)
                .findFirst()
                .orElseGet(() -> shipmentRepository.save(new Shipment(
                        UUID.randomUUID(), organizationId, salesOrderId, returnRequestId,
                        Shipment.DIRECTION_RETURN, Shipment.SOURCE_CREATED_BY_US)));
    }

    /**
     * The intent for this shipment, reused if it exists.
     *
     * <p>Re-calling the channel with the <em>same</em> intent id is safe and is the
     * point of {@link Capability#REQUEST_IDEMPOTENCY_KEY}: the channel recognises the
     * key and returns the original result instead of creating a second label. A new
     * intent id would look like a new request and get one.
     */
    private ChannelCallIntent findOrPrepareIntent(UUID organizationId, UUID channelConnectionId,
                                                   UUID shipmentId, UUID returnRequestId) {
        // Look first, insert second — deliberately not "insert and catch the duplicate".
        // A unique-violation inside a transaction marks it rollback-only in Postgres, so
        // catching DuplicateIntentException here would leave a transaction that can no
        // longer commit: every retry after the first would die on the way in, never reach
        // the channel, and never be counted as an attempt.
        //
        // The UNIQUE constraint still backs this up. Two genuinely concurrent attempts on
        // one shipment would have one of them fail loudly, which is correct — but retries
        // are sequential, and that is the path this method exists for.
        return intentService.findByTarget(organizationId, INTENT_SHIPMENT, shipmentId)
                .orElseGet(() -> intentService.prepare(organizationId, channelConnectionId, INTENT_SHIPMENT,
                        shipmentId, toJson(Map.of("returnRequestId", returnRequestId.toString()))));
    }

    /** plan §7: the channel makes the label, we only write down that it exists. */
    private Shipment observeChannelShipment(UUID organizationId, Context context) {
        log.info("Channel {} produces its own return label — recording it rather than requesting one",
                context.connection().getChannelType());

        return inTransaction(organizationId, () -> {
            Shipment shipment = shipmentRepository.save(new Shipment(
                    UUID.randomUUID(), organizationId, context.order().getId(), context.request().getId(),
                    Shipment.DIRECTION_RETURN, Shipment.SOURCE_PROVIDED_BY_CHANNEL));

            returnService.markReturnShipmentCreated(organizationId, context.request().getId());
            return shipment;
        });
    }

    /**
     * Pays the refund, or records that the channel paid it.
     *
     * <p>plan §7 puts this behind ADMIN, and the check is here rather than in a
     * controller so it holds for every caller.
     */
    public ReturnPayment issueRefund(AuthenticatedUser actor, UUID returnRequestId) {
        if (!actor.hasAtLeast(HubRole.ADMIN)) {
            auditLog.record(actor.organizationId(), actor.userId(), AuditLogService.PERMISSION_DENIED,
                    Map.of("action", "issue a refund", "role", actor.effectiveRole().name(), "required", "ADMIN"));
            throw new InsufficientRoleException(actor.effectiveRole(), HubRole.ADMIN);
        }

        UUID organizationId = actor.organizationId();
        Context context = loadContext(organizationId, returnRequestId);

        BigDecimal amount = inTransaction(organizationId,
                () -> returnService.refundAmount(organizationId, returnRequestId));

        ReturnPayment payment = inTransaction(organizationId, () -> returnPaymentRepository.save(new ReturnPayment(
                UUID.randomUUID(), organizationId, returnRequestId, amount,
                context.order().getCurrency(), actor.userId())));

        auditLog.record(organizationId, actor.userId(), AuditLogService.REFUND_AUTHORIZED,
                Map.of("returnRequestId", returnRequestId.toString(), "amount", amount.toPlainString()));

        if (!context.connector().capabilities().contains(Capability.REFUND_BY_US)) {
            return observeChannelRefund(organizationId, returnRequestId, payment);
        }

        return payViaChannel(organizationId, context, returnRequestId, payment, amount);
    }

    /**
     * plan §7: "Parayı kanal iade ediyorsa PARA_IADE_EDILDI bizim eylemimiz değil,
     * gözlemlediğimiz olaydır" — no call is made, and reconcile confirms it later.
     */
    private ReturnPayment observeChannelRefund(UUID organizationId, UUID returnRequestId, ReturnPayment payment) {
        log.info("Return {} is refunded by the channel — recording the observation, making no call", returnRequestId);

        return inTransaction(organizationId, () -> {
            ReturnPayment persisted = returnPaymentRepository.findById(payment.getId()).orElseThrow();
            persisted.markPaidByChannel(Instant.now());

            returnService.markRefunded(organizationId, returnRequestId);
            return persisted;
        });
    }

    private ReturnPayment payViaChannel(UUID organizationId, Context context, UUID returnRequestId,
                                         ReturnPayment payment, BigDecimal amount) {
        ChannelCallIntent intent = inTransaction(organizationId, () -> intentService.prepare(
                organizationId, context.connection().getId(), INTENT_REFUND, payment.getId(),
                toJson(Map.of("returnRequestId", returnRequestId.toString(), "amount", amount.toPlainString()))));

        // Committed BEFORE the call. A crash after this point leaves a SENT intent, which
        // recovery resolves by asking the channel — the alternative, a PREPARED intent, is
        // indistinguishable from "never sent" and would be retried into a second payment.
        inTransaction(organizationId, () -> {
            intentService.markSent(intent.getId());
            return null;
        });

        RefundResult result = context.connector().issueRefund(context.ref(),
                new RefundRequest(context.order().getChannelOrderNumber(), context.request().getChannelReturnId(),
                        amount, payment.getCurrency()),
                new CallIntentRef(intent.getId(), intent.getId().toString()));

        return inTransaction(organizationId, () -> {
            ReturnPayment persisted = returnPaymentRepository.findById(payment.getId()).orElseThrow();
            persisted.markPaid(result.channelRefundId(), Instant.now());
            intentService.recordResult(intent.getId(), toJson(Map.of("refundId", result.channelRefundId())));

            returnService.markRefunded(organizationId, returnRequestId);
            return persisted;
        });
    }

    /**
     * Finishes a refund whose call was made but whose result never got written — the
     * crash case. Asks the channel what happened instead of paying again; when the
     * channel cannot say either, the intent goes AMBIGUOUS and a human is asked.
     *
     * @return true when the outcome was established
     */
    public boolean resolveInFlightRefunds(UUID organizationId) {
        return inTransaction(organizationId, () -> {
            int resolved = intentService.recoverStuckIntents(intent -> {
                Optional<ChannelConnection> connection =
                        channelConnectionRepository.findById(intent.getChannelConnectionId());
                if (connection.isEmpty()) {
                    return Optional.empty();
                }

                PlatformConnector connector = connectorRegistry.require(connection.get().getChannelType());
                CallStatus status = connector.queryCallStatus(toRef(organizationId, connection.get()),
                        new CallIntentRef(intent.getId(), intent.getId().toString()));

                if (!status.resolved()) {
                    return Optional.empty();
                }
                applyRecoveredOutcome(organizationId, intent);
                return Optional.of(status.resultJson());
            }, java.time.Duration.ZERO);

            return resolved > 0;
        });
    }

    /**
     * A recovered intent has to move the domain too, not just the intent row. Otherwise
     * the refund is marked resolved while the payment still reads PENDING, and the next
     * operator to look at it authorises a second one.
     */
    private void applyRecoveredOutcome(UUID organizationId, ChannelCallIntent intent) {
        if (!INTENT_REFUND.equals(intent.getType())) {
            return;
        }
        returnPaymentRepository.findById(intent.getTargetReference()).ifPresent(payment -> {
            if (ReturnPayment.STATUS_PENDING.equals(payment.getStatus())) {
                payment.markPaid(null, Instant.now());
                returnService.markRefunded(organizationId, payment.getReturnRequestId());
                log.info("Recovered in-flight refund for payment {} — the channel confirms it was already paid",
                        payment.getId());
            }
        });
    }

    private Context loadContext(UUID organizationId, UUID returnRequestId) {
        return inTransaction(organizationId, () -> {
            ReturnRequest request = returnService.get(organizationId, returnRequestId);
            SalesOrder order = salesOrderRepository.findById(request.getSalesOrderId()).orElseThrow();
            ChannelConnection connection = channelConnectionRepository.findById(order.getChannelConnectionId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Return " + returnRequestId + " belongs to an order with no channel connection"));

            return new Context(request, order, connection,
                    connectorRegistry.require(connection.getChannelType()),
                    toRef(organizationId, connection));
        });
    }

    private ChannelConnectionRef toRef(UUID organizationId, ChannelConnection connection) {
        return new ChannelConnectionRef(connection.getId(), organizationId, connection.getChannelType(),
                credentialEncryptionService.decrypt(connection.getEncryptedCredentials(), connection.getKeyVersion()));
    }

    /**
     * Each step is its own transaction so nothing spans the network call — the same
     * reasoning as ChannelPushSender, and here it is what makes the intent's
     * "committed before the call" guarantee real rather than nominal.
     */
    private <T> T inTransaction(UUID organizationId, java.util.function.Supplier<T> work) {
        return transactionTemplate.execute(status -> {
            tenantContextService.setTransactionTenantContext(organizationId);
            return work.get();
        });
    }

    private String toJson(Map<String, String> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize intent summary", e);
        }
    }

    private record Context(ReturnRequest request, SalesOrder order, ChannelConnection connection,
                            PlatformConnector connector, ChannelConnectionRef ref) {
    }
}
