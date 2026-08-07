package com.ecommercehub.app.push;

import com.ecommercehub.app.backfill.ChannelBudgetRegistry;
import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.connector.ChannelItemRef;
import com.ecommercehub.connector.ChannelRateLimitedException;
import com.ecommercehub.connector.CredentialStatus;
import com.ecommercehub.connector.ItemResult;
import com.ecommercehub.connector.PlatformConnector;
import com.ecommercehub.connector.PriceUpdate;
import com.ecommercehub.connector.StockUpdate;
import com.ecommercehub.connector.ratelimit.BudgetClass;
import com.ecommercehub.connector.ratelimit.RateLimitBudget;
import com.ecommercehub.domain.channel.ChannelCircuitBreakerService;
import com.ecommercehub.domain.channel.ChannelConnection;
import com.ecommercehub.domain.channel.ChannelConnectionRepository;
import com.ecommercehub.domain.push.ChannelPushRow;
import com.ecommercehub.domain.push.ChannelPushService;
import com.ecommercehub.domain.push.ChannelPushStore;
import com.ecommercehub.domain.security.CredentialEncryptionService;
import com.ecommercehub.domain.tenant.TenantContextService;
import com.ecommercehub.ingest.ConnectorRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Plan §3 / Phase 4: drains one send window for one channel connection.
 *
 * <p>Everything pending for the connection goes out in a <em>single</em> batch call
 * (Plan §8: "a bulk signature is mandatory: 1000 SKUs is one call"), and every row is closed with
 * the generation compare-and-set Plan §3 spells out.
 *
 * <p><b>Deliberately three transactions, not one.</b> The claim commits, then the
 * channel is called with no transaction open, then the results are closed in a fresh
 * transaction. Wrapping all three in one would be simpler and would silently disable
 * the whole mechanism: an enqueue happening during the call could not see the claim
 * and could not commit a new generation, so the CAS could never fail, and "the value
 * changed mid-flight" — the one case this exists for — would be undetectable. It also
 * keeps a pooled connection from being held open for the duration of a channel's
 * response time.
 *
 * <p>Plan v5 Faz 5: {@code @Profile("worker")} — its only caller is {@code
 * SendChannelPushTaskHandler}, invoked exclusively by the task engine's consumer,
 * which only runs in the worker process (see {@code motor.worker.tuketici-aktif} in
 * application-api.yml). It also constructor-requires {@link ChannelBudgetRegistry},
 * itself worker-only, so this could not instantiate under "api" regardless.
 */
@Service
@Profile("worker")
public class ChannelPushSender implements com.ecommercehub.domain.push.ChannelPushWindowSender {

    private static final Logger log = LoggerFactory.getLogger(ChannelPushSender.class);
    private static final Duration RATE_LIMIT_BACKOFF = Duration.ofSeconds(30);

    private final ChannelPushStore pushStore;
    private final ChannelConnectionRepository channelConnectionRepository;
    private final CredentialEncryptionService credentialEncryptionService;
    private final ConnectorRegistry connectorRegistry;
    private final ChannelBudgetRegistry budgetRegistry;
    private final ChannelCircuitBreakerService circuitBreaker;
    private final TenantContextService tenantContextService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final PushProperties properties;

    public ChannelPushSender(ChannelPushStore pushStore,
                              ChannelConnectionRepository channelConnectionRepository,
                              CredentialEncryptionService credentialEncryptionService,
                              ConnectorRegistry connectorRegistry,
                              ChannelBudgetRegistry budgetRegistry,
                              ChannelCircuitBreakerService circuitBreaker,
                              TenantContextService tenantContextService,
                              TransactionTemplate transactionTemplate,
                              ObjectMapper objectMapper,
                              PushProperties properties) {
        this.pushStore = pushStore;
        this.channelConnectionRepository = channelConnectionRepository;
        this.credentialEncryptionService = credentialEncryptionService;
        this.connectorRegistry = connectorRegistry;
        this.budgetRegistry = budgetRegistry;
        this.circuitBreaker = circuitBreaker;
        this.tenantContextService = tenantContextService;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** @return how many rows the channel confirmed this window */
    @Override
    public int sendWindow(UUID organizationId, UUID channelConnectionId, String type) {
        if (!circuitBreaker.isCallable(channelConnectionId)) {
            log.debug("Skipping push window for connection {} — circuit open or connection not ACTIVE", channelConnectionId);
            return 0;
        }

        ClaimedWindow window = claim(organizationId, channelConnectionId, type);
        if (window == null) {
            return 0;
        }

        RateLimitBudget budget = budgetRegistry.forConnection(organizationId, channelConnectionId);
        if (!budget.tryAcquire(BudgetClass.INTERACTIVE)) {
            // No budget this tick — hand every row straight back rather than dropping it.
            inTenant(organizationId, () -> releaseAll(window.rows()));
            log.info("Push window for connection {} deferred — INTERACTIVE budget exhausted", channelConnectionId);
            return 0;
        }

        return callAndClose(organizationId, channelConnectionId, window, budget);
    }

    /** Commits the SENDING claim so a concurrent enqueue can see it and bump the generation past it. */
    private ClaimedWindow claim(UUID organizationId, UUID channelConnectionId, String type) {
        return transactionTemplate.execute(status -> {
            tenantContextService.setTransactionTenantContext(organizationId);

            List<ChannelPushRow> rows = pushStore.claimPending(
                    channelConnectionId, type, properties.getWindowBatchLimit());
            if (rows.isEmpty()) {
                return null;
            }

            ChannelConnection connection = channelConnectionRepository.findById(channelConnectionId)
                    .orElseThrow(() -> new IllegalArgumentException("No channel_connection " + channelConnectionId));

            // Credentials are decrypted here, inside the transaction that read the row,
            // so the network call below needs no database access at all.
            ChannelConnectionRef ref = new ChannelConnectionRef(connection.getId(), organizationId,
                    connection.getChannelType(),
                    credentialEncryptionService.decrypt(connection.getEncryptedCredentials(), connection.getKeyVersion()));

            return new ClaimedWindow(rows, type, connection.getChannelType(), ref);
        });
    }

    private int callAndClose(UUID organizationId, UUID channelConnectionId, ClaimedWindow window, RateLimitBudget budget) {
        PlatformConnector connector = connectorRegistry.require(window.channelType());
        boolean isPrice = ChannelPushService.TYPE_PRICE.equals(window.type());

        // Keyed by channelVariantId, never sku (Plan v5 §1) — it is the only one of the
        // three identifiers guaranteed present and unique per connection, so it is the
        // only safe correlation key for the bulk result.
        Map<String, ChannelPushRow> byChannelVariantId = new HashMap<>();
        for (ChannelPushRow row : window.rows()) {
            byChannelVariantId.put(readTargetValue(row).get("channelVariantId").asText(), row);
        }

        List<ItemResult> results;
        try {
            results = isPrice
                    ? connector.updatePrice(window.connectionRef(), buildPriceUpdates(window.rows()))
                    : connector.updateStock(window.connectionRef(), buildStockUpdates(window.rows()));
        } catch (ChannelRateLimitedException e) {
            budget.reportRateLimited(BudgetClass.INTERACTIVE, RATE_LIMIT_BACKOFF);
            inTenant(organizationId, () -> releaseAll(window.rows()));
            circuitBreaker.recordFailure(channelConnectionId, "rate limited: " + e.getMessage());
            log.warn("Push window for connection {} rate limited — {} row(s) returned to PENDING",
                    channelConnectionId, window.rows().size());
            return 0;
        } catch (RuntimeException e) {
            inTenant(organizationId, () -> releaseAll(window.rows()));
            handleCallFailure(organizationId, channelConnectionId, connector, window.connectionRef(), e);
            return 0;
        }

        circuitBreaker.recordSuccess(channelConnectionId);

        Integer confirmed = transactionTemplate.execute(status -> {
            tenantContextService.setTransactionTenantContext(organizationId);
            return closeResults(window.rows(), byChannelVariantId, results);
        });
        return confirmed == null ? 0 : confirmed;
    }

    private List<StockUpdate> buildStockUpdates(List<ChannelPushRow> rows) {
        List<StockUpdate> updates = new ArrayList<>(rows.size());
        for (ChannelPushRow row : rows) {
            JsonNode value = readTargetValue(row);
            updates.add(new StockUpdate(itemRefOf(value), value.get("quantity").asInt()));
        }
        return updates;
    }

    private List<PriceUpdate> buildPriceUpdates(List<ChannelPushRow> rows) {
        List<PriceUpdate> updates = new ArrayList<>(rows.size());
        for (ChannelPushRow row : rows) {
            JsonNode value = readTargetValue(row);
            updates.add(new PriceUpdate(itemRefOf(value), decimalOrNull(value.get("price")),
                    decimalOrNull(value.get("discountedPrice"))));
        }
        return updates;
    }

    private static ChannelItemRef itemRefOf(JsonNode value) {
        return new ChannelItemRef(value.get("channelVariantId").asText(), textOrNull(value.get("sku")),
                textOrNull(value.get("barcode")));
    }

    // Stored as a JSON string, not a number — see ChannelPushService.toPriceTargetValueJson
    // for why a jsonb-backed money value cannot be trusted to keep its original scale.
    private static java.math.BigDecimal decimalOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : new java.math.BigDecimal(node.asText());
    }

    /**
     * Plan §8 requires per-item results precisely so a partial failure can be expressed.
     * Successful rows close; failed ones go back to PENDING and ride the next window —
     * unless the failure is the row's {@link PushProperties#getMaxConsecutiveFailures()}th
     * in a row, in which case it goes to STUCK and the operator queue instead of
     * retrying forever (Plan v5 §1.7 gate 3).
     */
    private int closeResults(List<ChannelPushRow> claimed, Map<String, ChannelPushRow> byChannelVariantId,
                              List<ItemResult> results) {
        int confirmed = 0;
        Set<UUID> accountedFor = new HashSet<>();

        for (ItemResult result : results) {
            ChannelPushRow row = byChannelVariantId.get(result.referenceId());
            if (row == null) {
                log.warn("Channel returned a result for unknown reference {} — ignoring", result.referenceId());
                continue;
            }
            accountedFor.add(row.id());

            if (!result.success()) {
                handleItemRejection(row, result.error());
                continue;
            }
            if (pushStore.closeAsSent(row.id(), row.generation())) {
                confirmed++;
            } else {
                // CAS lost: a newer value landed mid-flight. The row is already back at
                // PENDING carrying it, so this success is deliberately discarded — the
                // channel is momentarily behind, which the next window fixes, instead of
                // permanently wrong, which nothing would.
                log.info("Push for variant {} superseded mid-flight — leaving the newer value queued", row.variantId());
            }
        }

        // A channel that simply omits an item from its response has neither accepted nor
        // rejected it; treating silence as success is how a value goes missing for good.
        for (ChannelPushRow row : claimed) {
            if (!accountedFor.contains(row.id())) {
                pushStore.releaseToPending(row.id(), row.generation());
                log.warn("Channel returned no result for variant {} — requeued", row.variantId());
            }
        }
        return confirmed;
    }

    /**
     * A per-item rejection (e.g. an identifier the channel does not recognise) counts
     * against the row's failure streak. Past the threshold the row goes STUCK instead
     * of PENDING and an operator_queue entry is raised — the alternative is the row
     * riding every future window forever, failing the same way each time (Plan v5
     * §1.7 gate 3).
     */
    private void handleItemRejection(ChannelPushRow row, String error) {
        ChannelPushStore.FailureOutcome outcome = pushStore.recordFailure(
                row.id(), row.generation(), properties.getMaxConsecutiveFailures());
        log.warn("Push for variant {} rejected by channel: {}", row.variantId(), error);

        if (outcome.stuck()) {
            pushStore.raiseStuckAlert(row.organizationId(), row.id(),
                    "Channel repeatedly rejected the push for variant " + row.variantId()
                            + " (" + outcome.consecutiveFailures() + " consecutive failures): " + error);
            log.warn("Push row {} (variant {}) marked STUCK after {} consecutive failures — routed to operator queue",
                    row.id(), row.variantId(), outcome.consecutiveFailures());
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * Distinguishes "the channel is unhappy with us" from "the channel is unhappy": a
     * failed call triggers a credential check, and only an actually rejected credential
     * takes the connection out of service until a human intervenes.
     */
    private void handleCallFailure(UUID organizationId, UUID channelConnectionId, PlatformConnector connector,
                                    ChannelConnectionRef connectionRef, RuntimeException cause) {
        log.warn("Push window for connection {} failed", channelConnectionId, cause);

        CredentialStatus credentialStatus;
        try {
            credentialStatus = connector.checkCredentials(connectionRef);
        } catch (RuntimeException e) {
            // Can't even ask — treat as a plain transient failure, not as revoked access.
            circuitBreaker.recordFailure(channelConnectionId, cause.getMessage());
            return;
        }

        if (credentialStatus.valid()) {
            circuitBreaker.recordFailure(channelConnectionId, cause.getMessage());
        } else {
            circuitBreaker.markCredentialsInvalid(organizationId, channelConnectionId, credentialStatus.reason());
        }
    }

    private void releaseAll(List<ChannelPushRow> rows) {
        rows.forEach(row -> pushStore.releaseToPending(row.id(), row.generation()));
    }

    private void inTenant(UUID organizationId, Runnable action) {
        transactionTemplate.executeWithoutResult(status -> {
            tenantContextService.setTransactionTenantContext(organizationId);
            action.run();
        });
    }

    private JsonNode readTargetValue(ChannelPushRow row) {
        try {
            return objectMapper.readTree(row.targetValueJson());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Corrupt channel_push.target_value on row " + row.id(), e);
        }
    }

    private record ClaimedWindow(List<ChannelPushRow> rows, String type, String channelType, ChannelConnectionRef connectionRef) {
    }
}
