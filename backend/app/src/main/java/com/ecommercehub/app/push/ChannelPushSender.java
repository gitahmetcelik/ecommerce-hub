package com.ecommercehub.app.push;

import com.ecommercehub.app.backfill.ChannelBudgetRegistry;
import com.ecommercehub.connector.ChannelConnectionRef;
import com.ecommercehub.connector.ChannelRateLimitedException;
import com.ecommercehub.connector.CredentialStatus;
import com.ecommercehub.connector.ItemResult;
import com.ecommercehub.connector.PlatformConnector;
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
 * plan §3 / Faz 4: drains one send window for one channel connection.
 *
 * <p>Everything pending for the connection goes out in a <em>single</em> batch call
 * (plan §8: "Toplu imza zorunlu — 1000 SKU = 1 çağrı"), and every row is closed with
 * the generation compare-and-set plan §3 spells out.
 *
 * <p><b>Deliberately three transactions, not one.</b> The claim commits, then the
 * channel is called with no transaction open, then the results are closed in a fresh
 * transaction. Wrapping all three in one would be simpler and would silently disable
 * the whole mechanism: an enqueue happening during the call could not see the claim
 * and could not commit a new generation, so the CAS could never fail, and "the value
 * changed mid-flight" — the one case this exists for — would be undetectable. It also
 * keeps a pooled connection from being held open for the duration of a channel's
 * response time.
 */
@Service
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
    public int sendWindow(UUID organizationId, UUID channelConnectionId) {
        if (!circuitBreaker.isCallable(channelConnectionId)) {
            log.debug("Skipping push window for connection {} — circuit open or connection not ACTIVE", channelConnectionId);
            return 0;
        }

        ClaimedWindow window = claim(organizationId, channelConnectionId);
        if (window == null) {
            return 0;
        }

        RateLimitBudget budget = budgetRegistry.forConnection(channelConnectionId);
        if (!budget.tryAcquire(BudgetClass.INTERACTIVE)) {
            // No budget this tick — hand every row straight back rather than dropping it.
            inTenant(organizationId, () -> releaseAll(window.rows()));
            log.info("Push window for connection {} deferred — INTERACTIVE budget exhausted", channelConnectionId);
            return 0;
        }

        return callAndClose(organizationId, channelConnectionId, window, budget);
    }

    /** Commits the SENDING claim so a concurrent enqueue can see it and bump the generation past it. */
    private ClaimedWindow claim(UUID organizationId, UUID channelConnectionId) {
        return transactionTemplate.execute(status -> {
            tenantContextService.setTransactionTenantContext(organizationId);

            List<ChannelPushRow> rows = pushStore.claimPending(
                    channelConnectionId, ChannelPushService.TYPE_STOCK, properties.getWindowBatchLimit());
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

            return new ClaimedWindow(rows, connection.getChannelType(), ref);
        });
    }

    private int callAndClose(UUID organizationId, UUID channelConnectionId, ClaimedWindow window, RateLimitBudget budget) {
        PlatformConnector connector = connectorRegistry.require(window.channelType());

        Map<String, ChannelPushRow> bySku = new HashMap<>();
        List<StockUpdate> updates = new ArrayList<>(window.rows().size());
        for (ChannelPushRow row : window.rows()) {
            JsonNode value = readTargetValue(row);
            String sku = value.get("sku").asText();
            bySku.put(sku, row);
            updates.add(new StockUpdate(sku, value.get("quantity").asInt()));
        }

        List<ItemResult> results;
        try {
            results = connector.updateStock(window.connectionRef(), updates);
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
            return closeResults(window.rows(), bySku, results);
        });
        return confirmed == null ? 0 : confirmed;
    }

    /**
     * plan §8 requires per-item results precisely so a partial failure can be expressed.
     * Successful rows close; failed ones go back to PENDING and ride the next window.
     */
    private int closeResults(List<ChannelPushRow> claimed, Map<String, ChannelPushRow> bySku, List<ItemResult> results) {
        int confirmed = 0;
        Set<UUID> accountedFor = new HashSet<>();

        for (ItemResult result : results) {
            ChannelPushRow row = bySku.get(result.referenceId());
            if (row == null) {
                log.warn("Channel returned a result for unknown reference {} — ignoring", result.referenceId());
                continue;
            }
            accountedFor.add(row.id());

            if (!result.success()) {
                pushStore.releaseToPending(row.id(), row.generation());
                log.warn("Push for variant {} rejected by channel: {}", row.variantId(), result.error());
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

    private record ClaimedWindow(List<ChannelPushRow> rows, String channelType, ChannelConnectionRef connectionRef) {
    }
}
